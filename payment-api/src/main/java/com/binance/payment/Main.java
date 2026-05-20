package com.binance.payment;

import com.binance.payment.api.PaymentApiServer;
import com.binance.payment.service.InMemoryPaymentRepository;
import com.binance.payment.service.PaymentRepository;
import com.binance.payment.service.PaymentService;

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

        PaymentRepository repository = new InMemoryPaymentRepository();
        PaymentService    service    = new PaymentService(repository);
        PaymentApiServer  server     = new PaymentApiServer(port, service);
        server.start();

        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║          Binance Payment API (P1)            ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.printf("  REST API : http://0.0.0.0:%d/api/v1/payments%n", port);
        System.out.printf("  Health   : http://0.0.0.0:%d/api/v1/health%n", port);
        System.out.println("  Repo     : in-memory (no external DB)");
        System.out.println("  Press Ctrl+C to stop.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            server.stop();
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
