package com.binance.payment.metrics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Prometheus exposition for the payment API, with no client library.
 *
 * <p><b>Why hand-rolled.</b> A metrics client would pull in a transitive
 * dependency tree for roughly 150 lines of text formatting. The Dockerfile
 * already argues the same way about the JRE runtime image: do not ship what you
 * do not need.
 *
 * <p><b>Why this exists at all.</b> Before this class, every signal about the
 * service came from outside it, as a black-box probe of {@code /health}. That
 * answers "is it up", but not the three questions that actually define a
 * request-driven service: how many requests, how many failed, and how long they
 * took. The availability SLO was therefore measured against synthetic probe
 * traffic rather than against requests real callers made.
 *
 * <p><b>On the JVM metrics below.</b> They are read in-process from
 * {@link ManagementFactory}, so unlike {@code jstat} they need no attach
 * handshake. That is deliberately <i>not</i> a replacement for the external
 * jstat textfile collector: during incident #1 the GC starved every thread in
 * this JVM, and a scrape served by this process would have starved with them.
 * In-process metrics go silent in exactly the scenario you most need them.
 * The external collector stays as the independent observer.
 */
public final class Metrics {

    /** Histogram bucket upper bounds, in seconds. */
    private static final double[] BUCKETS = {
        0.001, 0.005, 0.01, 0.025, 0.05, 0.1,
        0.25,   // the SLO latency threshold - see docs/slo.md
        0.5, 1, 2.5, 5, 10
    };

    /**
     * Request methods that may become a label value. Anything else collapses to
     * "other", so a caller cannot mint label values by sending odd verbs.
     */
    // BOUNDED-BY: seven string literals, fixed at compile time. It is the
    // allow-list that bounds the `method` label, so it is by definition not
    // something traffic can add to.
    private static final Set<String> KNOWN_METHODS =
        Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    // BOUNDED-BY: label cardinality is bounded by construction, not by eviction.
    // A key is (route, method, status). `route` is only ever one of the string
    // literals PaymentApiServer passes to observe() - the registered HTTP
    // contexts, never a raw request path. `method` is filtered through
    // KNOWN_METHODS above. `status` is an HTTP code this server emits. The
    // product is a few hundred entries at most, and cannot grow with traffic,
    // request content, or uptime.
    //
    // This declaration is the whole point of the gate. A metrics registry keyed
    // on a raw URI path, a user id or an idempotency key would be the same
    // defect as the OrderBook collections in incident #1 - a long-lived map that
    // only ever grows - except that it takes the monitoring system down with the
    // service.
    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();

    // BOUNDED-BY: the same key space as `counters` above, but keyed on
    // (route, method) rather than (route, method, status), so it is strictly
    // smaller. Each value is a fixed-width array sized by BUCKETS.
    private final ConcurrentHashMap<String, Histogram> histograms = new ConcurrentHashMap<>();

    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();

    /** Records one served request. Called from a single place: the send() path. */
    public void observe(String route, String method, int status, long durationNanos) {
        String m = KNOWN_METHODS.contains(method) ? method : "other";
        counters.computeIfAbsent(route + " " + m + " " + status,
                                 k -> new LongAdder()).increment();
        histograms.computeIfAbsent(route + " " + m, k -> new Histogram())
                  .record(durationNanos / 1_000_000_000.0);
    }

    /** Renders current state in the Prometheus text exposition format. */
    public String render() {
        StringBuilder out = new StringBuilder(4096);

        out.append("# HELP payment_requests_total Requests served, by route, method and status.\n");
        out.append("# TYPE payment_requests_total counter\n");
        for (Map.Entry<String, LongAdder> e : counters.entrySet()) {
            String[] k = e.getKey().split(" ", -1);
            out.append("payment_requests_total{route=\"").append(k[0])
               .append("\",method=\"").append(k[1])
               .append("\",status=\"").append(k[2])
               .append("\"} ").append(e.getValue().sum()).append("\n");
        }

        out.append("# HELP payment_request_duration_seconds Request duration.\n");
        out.append("# TYPE payment_request_duration_seconds histogram\n");
        for (Map.Entry<String, Histogram> e : histograms.entrySet()) {
            String[] k = e.getKey().split(" ", -1);
            e.getValue().render(out, k[0], k[1]);
        }

        // JVM, read in-process - no attach handshake required.
        appendGauge(out, "jvm_memory_heap_used_bytes",
                    "Heap currently in use.", memory.getHeapMemoryUsage().getUsed());
        appendGauge(out, "jvm_memory_heap_max_bytes",
                    "Heap ceiling (-Xmx, or the ergonomic default).",
                    memory.getHeapMemoryUsage().getMax());
        appendGauge(out, "jvm_threads_live", "Live threads.", threads.getThreadCount());
        appendGauge(out, "jvm_process_uptime_seconds", "Process uptime.",
                    runtime.getUptime() / 1000.0);

        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        out.append("# HELP jvm_gc_collections_total Completed collections, by collector.\n");
        out.append("# TYPE jvm_gc_collections_total counter\n");
        for (GarbageCollectorMXBean gc : gcs) {
            out.append("jvm_gc_collections_total{gc=\"").append(escape(gc.getName()))
               .append("\"} ").append(gc.getCollectionCount()).append("\n");
        }
        out.append("# HELP jvm_gc_seconds_total Cumulative collection time, by collector.\n");
        out.append("# TYPE jvm_gc_seconds_total counter\n");
        for (GarbageCollectorMXBean gc : gcs) {
            out.append("jvm_gc_seconds_total{gc=\"").append(escape(gc.getName()))
               .append("\"} ").append(num(gc.getCollectionTime() / 1000.0)).append("\n");
        }

        return out.toString();
    }

    private static void appendGauge(StringBuilder out, String name, String help, double value) {
        out.append("# HELP ").append(name).append(" ").append(help).append("\n");
        out.append("# TYPE ").append(name).append(" gauge\n");
        out.append(name).append(" ").append(num(value)).append("\n");
    }

    /**
     * Formats a double without scientific notation.
     *
     * <p>Java renders 3126853632.0 as "3.126853632E9". Prometheus does parse
     * that, but every other consumer of a scrape - grep, awk, a human reading
     * curl output during an incident - has to stop and decode it. Plain
     * decimal costs nothing and stays readable at 3am.
     */
    private static String num(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return new java.math.BigDecimal(v).round(new java.math.MathContext(12))
                                          .stripTrailingZeros().toPlainString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** Cumulative-bucket histogram. Fixed width, so it cannot grow. */
    private static final class Histogram {
        private final LongAdder[] buckets = new LongAdder[BUCKETS.length + 1]; // +1 for +Inf
        private final LongAdder count = new LongAdder();
        private final DoubleAdder sum = new DoubleAdder();

        Histogram() {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = new LongAdder();
            }
        }

        void record(double seconds) {
            count.increment();
            sum.add(seconds);
            for (int i = 0; i < BUCKETS.length; i++) {
                if (seconds <= BUCKETS[i]) {
                    buckets[i].increment();
                    return;
                }
            }
            buckets[BUCKETS.length].increment();   // +Inf
        }

        void render(StringBuilder out, String route, String method) {
            String labels = "route=\"" + route + "\",method=\"" + method + "\"";
            long cumulative = 0;
            for (int i = 0; i < BUCKETS.length; i++) {
                cumulative += buckets[i].sum();
                out.append("payment_request_duration_seconds_bucket{").append(labels)
                   .append(",le=\"").append(BUCKETS[i]).append("\"} ")
                   .append(cumulative).append("\n");
            }
            cumulative += buckets[BUCKETS.length].sum();
            out.append("payment_request_duration_seconds_bucket{").append(labels)
               .append(",le=\"+Inf\"} ").append(cumulative).append("\n");
            out.append("payment_request_duration_seconds_sum{").append(labels)
               .append("} ").append(num(sum.sum())).append("\n");
            out.append("payment_request_duration_seconds_count{").append(labels)
               .append("} ").append(count.sum()).append("\n");
        }
    }
}
