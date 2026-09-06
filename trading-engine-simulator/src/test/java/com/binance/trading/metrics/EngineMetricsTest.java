package com.binance.trading.metrics;

import com.binance.trading.engine.TradingEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine's /metrics is the source for the work-progress SLI - the one SLO in
 * this repository that has actually been over budget. These tests guard the two
 * properties that matter for that role.
 */
class EngineMetricsTest {

    private static String render() {
        return new EngineMetrics(new TradingEngine()).render();
    }

    @Test
    @DisplayName("Every metric carries HELP and TYPE, and no value uses scientific notation")
    void expositionIsWellFormed() {
        String out = render();
        for (String name : new String[]{
                "engine_running", "engine_orders_generated_total",
                "engine_orders_retained", "engine_orders_retention_limit",
                "engine_duplicate_order_ids", "engine_last_price"}) {
            assertTrue(out.contains("# HELP " + name + " "), "missing HELP for " + name);
            assertTrue(out.contains("# TYPE " + name + " "), "missing TYPE for " + name);
        }
        for (String line : out.split("\n")) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String value = line.substring(line.lastIndexOf(' ') + 1);
            assertFalse(value.contains("E") || value.contains("e"),
                "value rendered in scientific notation: " + line);
        }
    }

    @Test
    @DisplayName("The retained gauge is bounded by the limit, unlike the submitted counter")
    void retainedIsTheCanaryNotSubmitted() {
        TradingEngine engine = new TradingEngine();
        int limit = engine.getOrderBook().getRetention();

        // Push well past the retention ceiling so the two numbers diverge.
        for (int i = 0; i < limit * 3; i++) {
            com.binance.trading.model.Order o = new com.binance.trading.model.Order();
            o.setOrderId("ORD-" + i);
            o.setType((i % 2 == 0) ? "BUY" : "SELL");
            o.setSymbol("BTCUSDT");
            o.setAmount("1.0");
            o.setPrice(new java.math.BigDecimal("100.0"));
            o.setTimestamp(System.currentTimeMillis());
            engine.getOrderBook().addOrder(o);
        }

        String out = new EngineMetrics(engine).render();
        long retained  = valueOf(out, "engine_orders_retained");
        long submitted = valueOf(out, "engine_orders_submitted_total");

        // This is the whole point of renaming the canary. The old textfile metric
        // was wired to the submission counter, which climbs forever whether or
        // not the collection is bounded - so it could never have detected the
        // incident #1 defect. The retained gauge flattens against the limit.
        assertEquals(limit * 3, submitted, "submitted must count every addOrder");
        assertTrue(retained <= limit,
            "retained (" + retained + ") exceeded the retention limit (" + limit + ")");
        assertTrue(retained < submitted,
            "retained must diverge from submitted once the bound engages");
    }

    @Test
    @DisplayName("No jvm_* metrics leak into the engine exposition")
    void noJvmMetricsByDesign() {
        // jvm_* belongs to the external jstat collector (job=node). Exposing it
        // here too would give one JVM two series and double every JVM alert.
        for (String line : render().split("\n")) {
            assertFalse(line.startsWith("jvm_") || line.startsWith("# HELP jvm_"),
                "engine /metrics must not expose jvm_* : " + line);
        }
    }

    private static long valueOf(String exposition, String metric) {
        for (String line : exposition.split("\n")) {
            if (line.startsWith(metric + " ")) {
                return Long.parseLong(line.substring(metric.length() + 1).trim());
            }
        }
        throw new AssertionError("metric not found: " + metric);
    }
}
