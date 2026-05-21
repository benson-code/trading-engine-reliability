package com.binance.payment.api;

import com.binance.payment.model.PaymentRequest;
import com.binance.payment.model.PaymentResponse;
import com.binance.payment.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Embedded JDK HTTP server exposing the real {@link PaymentService} via REST —
 * the first runnable Payment API (P1). Replaces the WireMock stubs the test
 * suite previously asserted against, so payment tests can exercise the actual
 * idempotency / validation / async logic.
 *
 * <p>Endpoints (contract matches the existing RestAssured expectations):</p>
 * <ul>
 *   <li>{@code POST /api/v1/payments} → {@code 202 Accepted} for a new payment
 *       ({@code 200 OK} on an idempotent retry of the same key), body carries
 *       {@code payment_id}, {@code status:PENDING}, {@code job_id}.</li>
 *   <li>{@code GET /api/v1/payments/{jobId}/status} → {@code 200} with the
 *       job's current state ({@code PENDING} → {@code SUCCESS}).</li>
 *   <li>{@code GET /api/v1/health} → {@code 200 {"status":"UP"}} (readiness
 *       probe for the future container/K8s work).</li>
 * </ul>
 *
 * <p>Validation failures from {@link PaymentService} surface as {@code 400}
 * ({@code INVALID_AMOUNT} for amount, {@code VALIDATION_ERROR} otherwise);
 * insufficient balance surfaces as {@code 402 Payment Required}.</p>
 */
public class PaymentApiServer {

    private final HttpServer server;
    private final PaymentService paymentService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int port;

    /** Expected {@code X-API-Key}. When null/blank, authentication is disabled. */
    private final String apiKey;

    /** Async settlement: a created payment moves PENDING → SUCCESS after this delay. */
    private final long settleDelayMs;
    private final ScheduledExecutorService settler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "payment-settler");
                t.setDaemon(true);
                return t;
            });

    /** jobId → settlement state, populated on accept, flipped to SUCCESS by {@link #settler}. */
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();

    private record Job(String paymentId, String status) {}

    public PaymentApiServer(int port, PaymentService paymentService) throws IOException {
        this(port, paymentService, 50, null);
    }

    public PaymentApiServer(int port, PaymentService paymentService, long settleDelayMs) throws IOException {
        this(port, paymentService, settleDelayMs, null);
    }

    public PaymentApiServer(int port, PaymentService paymentService, long settleDelayMs, String apiKey)
            throws IOException {
        this.paymentService = paymentService;
        this.settleDelayMs = settleDelayMs;
        this.apiKey = (apiKey == null || apiKey.isBlank()) ? null : apiKey;
        // port 0 → the OS binds a free ephemeral port atomically. No
        // probe-close-rebind window (eliminates the BUG-02-class TOCTOU).
        this.server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        this.port = server.getAddress().getPort();   // the actual bound port
        server.createContext("/api/v1/payments", this::handlePayments);
        server.createContext("/api/v1/health",   this::handleHealth);
        server.setExecutor(Executors.newFixedThreadPool(8));
    }

    public void start() { server.start(); }

    public void stop() {
        server.stop(0);
        settler.shutdownNow();
    }

    public int getPort() { return port; }

    // ── /api/v1/payments[/{jobId}/status] ────────────────────────────────────

    private void handlePayments(HttpExchange ex) throws IOException {
        cors(ex);
        if (preflight(ex)) return;
        if (unauthorized(ex)) return;   // /health stays exempt — it does not call this

        String method = ex.getRequestMethod();
        String[] parts = ex.getRequestURI().getPath().split("/");
        // ["", "api", "v1", "payments"]                       → create
        // ["", "api", "v1", "payments", "{jobId}", "status"]  → status poll

        if ("POST".equals(method) && parts.length == 4) {
            handleCreate(ex);
        } else if ("GET".equals(method) && parts.length == 6 && "status".equals(parts[5])) {
            handleStatus(ex, parts[4]);
        } else {
            send(ex, 405, toJson(Map.of("error", "Method Not Allowed")));
        }
    }

    private void handleCreate(HttpExchange ex) throws IOException {
        // Cap the body at 64 KB — same OOM guard as the trading engine (BUG-07).
        byte[] body = ex.getRequestBody().readNBytes(65_536);
        PaymentRequest request;
        try {
            request = mapper.readValue(body, PaymentRequest.class);
        } catch (Exception e) {
            send(ex, 400, toJson(Map.of("error", "BAD_REQUEST", "message", "Malformed JSON body")));
            return;
        }

        // Detect retry BEFORE processing so we can answer 200 (replay) vs 202 (new).
        boolean replay = paymentService.isAlreadyProcessed(request.getIdempotencyKey());

        PaymentResponse resp;
        try {
            resp = paymentService.processPayment(request);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            String code = msg.contains("positive")  ? "INVALID_AMOUNT"
                        : msg.contains("precision") ? "INVALID_PRECISION"
                        : "VALIDATION_ERROR";
            send(ex, 400, toJson(Map.of("error", code, "message", msg)));
            return;
        } catch (java.util.NoSuchElementException e) {
            send(ex, 404, toJson(Map.of("error", "ACCOUNT_NOT_FOUND",
                    "message", String.valueOf(e.getMessage()))));
            return;
        } catch (com.binance.payment.service.CurrencyMismatchException e) {
            send(ex, 422, toJson(Map.of("error", "CURRENCY_MISMATCH",
                    "message", String.valueOf(e.getMessage()))));
            return;
        } catch (com.binance.payment.service.InsufficientBalanceException e) {
            // Specific subclass — must be caught BEFORE IllegalStateException.
            send(ex, 402, toJson(Map.of("error", "INSUFFICIENT_BALANCE",
                    "message", String.valueOf(e.getMessage()))));
            return;
        } catch (IllegalStateException e) {
            // Unexpected server-side failure (e.g. an SQL error that slipped past
            // service-layer validation). Surface as 500, not 402.
            send(ex, 500, toJson(Map.of("error", "INTERNAL_ERROR",
                    "message", String.valueOf(e.getMessage()))));
            return;
        }

        // Register the job and schedule async settlement (only on first create).
        jobs.computeIfAbsent(resp.getJobId(), jid -> {
            settler.schedule(
                    () -> jobs.put(jid, new Job(resp.getPaymentId(), "SUCCESS")),
                    settleDelayMs, TimeUnit.MILLISECONDS);
            return new Job(resp.getPaymentId(), "PENDING");
        });

        send(ex, replay ? 200 : 202, toJson(resp));
    }

    private void handleStatus(HttpExchange ex, String jobId) throws IOException {
        Job job = jobs.get(jobId);
        if (job == null) {
            send(ex, 404, toJson(Map.of("error", "JOB_NOT_FOUND", "job_id", jobId)));
            return;
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("payment_id", job.paymentId());
        resp.put("job_id",     jobId);
        resp.put("status",     job.status());
        resp.put("message", "SUCCESS".equals(job.status())
                ? "Payment completed" : "Payment is being processed");
        send(ex, 200, toJson(resp));
    }

    // ── /api/v1/health ───────────────────────────────────────────────────────

    private void handleHealth(HttpExchange ex) throws IOException {
        cors(ex);
        if (preflight(ex)) return;
        send(ex, 200, toJson(Map.of("status", "UP")));
    }

    // ── Helpers (mirrors TradingApiServer) ───────────────────────────────────

    /**
     * Enforces the {@code X-API-Key} header when an API key is configured.
     * Returns true (and sends 401) when the request is rejected. No-op when
     * authentication is disabled (no key configured).
     */
    private boolean unauthorized(HttpExchange ex) throws IOException {
        if (apiKey == null) return false;   // auth disabled
        String provided = ex.getRequestHeaders().getFirst("X-API-Key");
        // Constant-time comparison — avoids leaking the key via response timing.
        boolean ok = provided != null && java.security.MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                apiKey.getBytes(StandardCharsets.UTF_8));
        if (ok) return false;
        send(ex, 401, toJson(Map.of("error", "UNAUTHORIZED",
                "message", "Missing or invalid X-API-Key")));
        return true;
    }

    private void cors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Idempotency-Key, X-API-Key");
    }

    private boolean preflight(HttpExchange ex) throws IOException {
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); }
        catch (Exception e) { return "{\"error\":\"Serialization failed\"}"; }
    }
}
