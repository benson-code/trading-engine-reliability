package com.binance.payment.metrics;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code BOUNDED-BY} comment on {@link Metrics} is a claim. This is the
 * evidence.
 *
 * <p>An unbounded metric registry is the same defect class as the OrderBook
 * collections in incident #1 - a long-lived map that only ever grows - except
 * that it takes the monitoring system down along with the service. The usual way
 * it happens is not malice but convenience: someone labels a metric with the raw
 * request path, or a user id, or an idempotency key, and cardinality becomes a
 * function of traffic instead of a function of the code.
 *
 * <p>These tests fail if that ever becomes true here.
 */
@Epic("Observability")
@Feature("Metrics cardinality")
class MetricsTest {

    private static final Pattern SERIES =
        Pattern.compile("^(payment_requests_total|payment_request_duration_seconds_count)\\{[^}]*\\}",
                        Pattern.MULTILINE);

    /** Distinct time series currently exposed for the request metrics. */
    private static Set<String> seriesOf(Metrics m) {
        Set<String> found = new HashSet<>();
        Matcher matcher = SERIES.matcher(m.render());
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    @Test
    @Story("Hostile method names cannot mint new label values")
    @DisplayName("10,000 distinct HTTP methods collapse to a bounded label set")
    void hostileMethodsDoNotGrowCardinality() {
        Metrics m = new Metrics();

        for (int i = 0; i < 10_000; i++) {
            m.observe("/api/v1/payments", "EVIL-METHOD-" + i, 200, 1_000_000L);
        }

        // Every unknown verb must collapse to "other": one counter series and
        // one histogram series, not 10,000 of each.
        Set<String> series = seriesOf(m);
        assertEquals(2, series.size(),
            "10,000 distinct methods produced " + series.size()
            + " series. Cardinality is a function of traffic, which is the defect "
            + "this test exists to prevent. Series: " + series);
        assertTrue(m.render().contains("method=\"other\""),
            "unknown methods should be recorded under method=\"other\"");
    }

    @Test
    @Story("Cardinality is a function of the code, not of traffic")
    @DisplayName("Series count stays flat as request volume grows 100x")
    void seriesCountIsIndependentOfVolume() {
        Metrics m = new Metrics();

        for (int i = 0; i < 100; i++) {
            m.observe("/api/v1/payments", "POST", 202, 1_000_000L);
        }
        int afterHundred = seriesOf(m).size();

        for (int i = 0; i < 10_000; i++) {
            m.observe("/api/v1/payments", "POST", 202, 1_000_000L);
        }
        int afterTenThousand = seriesOf(m).size();

        assertEquals(afterHundred, afterTenThousand,
            "series count grew with request volume: " + afterHundred
            + " -> " + afterTenThousand);
    }

    @Test
    @Story("Known routes, methods and statuses are still distinguished")
    @DisplayName("Bounding does not flatten the labels that matter")
    void legitimateLabelsAreStillDistinct() {
        Metrics m = new Metrics();
        m.observe("/api/v1/payments", "POST", 202, 1_000_000L);
        m.observe("/api/v1/payments", "POST", 400, 1_000_000L);
        m.observe("/api/v1/payments", "GET", 200, 1_000_000L);
        m.observe("/api/v1/health", "GET", 200, 1_000_000L);

        String out = m.render();
        assertTrue(out.contains("route=\"/api/v1/payments\",method=\"POST\",status=\"202\""), out);
        assertTrue(out.contains("route=\"/api/v1/payments\",method=\"POST\",status=\"400\""), out);
        assertTrue(out.contains("route=\"/api/v1/payments\",method=\"GET\",status=\"200\""), out);
        assertTrue(out.contains("route=\"/api/v1/health\",method=\"GET\",status=\"200\""), out);
    }

    @Test
    @Story("Histogram buckets are cumulative and include the SLO boundary")
    @DisplayName("A 300ms request lands above the 250ms SLO bucket, not below it")
    void histogramBucketsAreCumulativeAndCarryTheSloBoundary() {
        Metrics m = new Metrics();
        m.observe("/api/v1/payments", "POST", 202, 300_000_000L);   // 300ms

        String out = m.render();
        String labels = "route=\"/api/v1/payments\",method=\"POST\"";

        // 250ms is the SLO latency threshold (docs/slo.md). A 300ms request is a
        // budget-consuming event, so it must NOT be counted in the le="0.25"
        // bucket - otherwise the SLI silently reports better than reality.
        assertTrue(out.contains("_bucket{" + labels + ",le=\"0.25\"} 0"),
            "300ms must fall outside the 250ms SLO bucket:\n" + out);
        assertTrue(out.contains("_bucket{" + labels + ",le=\"0.5\"} 1"),
            "300ms must be counted in the 500ms bucket:\n" + out);
        assertTrue(out.contains("_bucket{" + labels + ",le=\"+Inf\"} 1"), out);
        assertTrue(out.contains("_count{" + labels + "} 1"), out);
    }

    @Test
    @Story("Concurrent recording does not lose or duplicate observations")
    @DisplayName("16 threads x 1,000 observations produce exactly 16,000")
    void concurrentObservationsAreExact() throws Exception {
        Metrics m = new Metrics();
        int threads = 16, perThread = 1_000;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        m.observe("/api/v1/payments", "POST", 202, 1_000_000L);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish in time");
        pool.shutdownNow();

        String out = m.render();
        assertTrue(out.contains(
                "payment_requests_total{route=\"/api/v1/payments\",method=\"POST\",status=\"202\"} "
                + (threads * perThread)),
            "counter lost or duplicated observations under concurrency:\n" + out);
        assertTrue(out.contains(
                "payment_request_duration_seconds_count{route=\"/api/v1/payments\",method=\"POST\"} "
                + (threads * perThread)),
            "histogram count disagrees with the counter:\n" + out);
    }

    @Test
    @Story("Exposition output is machine-parseable")
    @DisplayName("Every metric carries HELP and TYPE, and no value uses scientific notation")
    void expositionFormatIsWellFormed() {
        Metrics m = new Metrics();
        m.observe("/api/v1/health", "GET", 200, 1_000_000L);
        String out = m.render();

        for (String name : new String[]{
                "payment_requests_total", "payment_request_duration_seconds",
                "jvm_memory_heap_used_bytes", "jvm_gc_collections_total"}) {
            assertTrue(out.contains("# HELP " + name + " "), "missing HELP for " + name);
            assertTrue(out.contains("# TYPE " + name + " "), "missing TYPE for " + name);
        }

        // Prometheus parses exponent notation, but grep and awk at 3am do not.
        for (String line : out.split("\n")) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String value = line.substring(line.lastIndexOf(' ') + 1);
            assertTrue(!value.contains("E") && !value.contains("e"),
                "value rendered in scientific notation: " + line);
        }
    }
}
