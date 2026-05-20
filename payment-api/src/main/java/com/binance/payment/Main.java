package com.binance.payment;

import com.binance.payment.api.PaymentApiServer;
import com.binance.payment.service.InMemoryPaymentRepository;
import com.binance.payment.service.JdbcPaymentRepository;
import com.binance.payment.service.PaymentRepository;
import com.binance.payment.service.PaymentService;

import java.math.BigDecimal;

/**
 * Entry point for the standalone Payment API (P1).
 *
 * Port:
 *   8091 → REST API (payments, status, health)
 *
 * Usage:
 *   mvn package -pl payment-api -am -DskipTests
 *   java -jar payment-api/target/payment-api-qa-framework-1.0.0.jar
 *
 * Port resolution: arg[0] → env PAYMENT_PORT → 8091.
 *
 * NOTE: P1 wires an in-memory repository so the service runs with no external
 *       DB. {@link PaymentRepository} is the seam — P2 swaps in a JDBC/H2
 *       implementation here without touching the API or service layers.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        int port = resolvePort(args);

        // PAYMENT_REPO=jdbc → real DB (H2 in-mem, MySQL mode) with strict
        // account semantics; default 'memory' keeps the zero-friction demo.
        String repoMode = System.getenv()
                .getOrDefault("PAYMENT_REPO", "memory").trim().toLowerCase();
        String repoLabel;
        PaymentRepository repository;
        if ("jdbc".equals(repoMode)) {
            JdbcPaymentRepository jdbc = new JdbcPaymentRepository();
            // Composition root seeds a demo account so a fresh `java -jar`
            // smoke works; unknown users still correctly fail (404).
            jdbc.seedAccount("USER_DEMO", new BigDecimal("1000000"));
            repository = jdbc;
            repoLabel  = "JDBC H2 (MySQL mode) — seeded account: USER_DEMO";
        } else {
            repository = new InMemoryPaymentRepository();
            repoLabel  = "in-memory (no external DB)";
        }

        PaymentService   service = new PaymentService(repository);
        PaymentApiServer server  = new PaymentApiServer(port, service);
        server.start();
        int boundPort = server.getPort();

        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║            Binance Payment API               ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.printf("  REST API : http://0.0.0.0:%d/api/v1/payments%n", boundPort);
        System.out.printf("  Health   : http://0.0.0.0:%d/api/v1/health%n", boundPort);
        System.out.printf("  Repo     : %s%n", repoLabel);
        System.out.println("  Press Ctrl+C to stop.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            server.stop();
            if (repository instanceof AutoCloseable ac) {
                try { ac.close(); } catch (Exception ignored) { }
            }
            System.out.println("Done.");
        }));

        Thread.currentThread().join();
    }

    private static int resolvePort(String[] args) {
        if (args.length > 0) {
            try { return Integer.parseInt(args[0]); }
            catch (NumberFormatException ignored) {}
        }
        String env = System.getenv("PAYMENT_PORT");
        if (env != null && !env.isBlank()) {
            try { return Integer.parseInt(env.trim()); }
            catch (NumberFormatException ignored) {}
        }
        return 8091;
    }
}
