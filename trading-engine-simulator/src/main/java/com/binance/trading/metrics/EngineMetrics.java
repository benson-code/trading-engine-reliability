package com.binance.trading.metrics;

import com.binance.trading.engine.TradingEngine;

/**
 * Prometheus exposition for the trading engine, read straight from engine state.
 *
 * <p><b>What this replaces.</b> These numbers used to reach Prometheus like this:
 *
 * <pre>
 *   engine memory -&gt; JSON /api/v1/status -&gt; cron curl -&gt; python -&gt; .prom file
 *                 -&gt; node_exporter textfile collector -&gt; Prometheus
 * </pre>
 *
 * Six hops and a 30-second cron for data the process already holds in a field.
 * Worse, that path was never an independent observation: the script sources the
 * values from the engine's own API, so when the engine cannot answer, neither
 * can the collector. It bought latency and a cron dependency and nothing else.
 *
 * <p><b>What deliberately does NOT move here.</b> This class exposes no
 * {@code jvm_*} metrics. Those stay with the external {@code jstat} collector,
 * for two reasons:
 * <ol>
 *   <li><b>Independence.</b> {@code jstat} attaches from outside the JVM. During
 *       incident #1 the GC starved every thread in this process; a scrape served
 *       from inside would have starved with it. The external collector is the
 *       observer that survives the failure it is meant to describe.</li>
 *   <li><b>Name collisions.</b> Exposing {@code jvm_oldgen_utilization_ratio}
 *       here as well would give one JVM two series, and every rule matching that
 *       name would fire twice for a single fault - the alert fatigue this stack
 *       argues against.</li>
 * </ol>
 *
 * <p><b>On {@code engine_orders_retained}.</b> The old textfile exporter
 * published {@code engine_orders_total} with the help text "orders currently
 * retained - this climbs forever when unbounded". It was wired to
 * {@code totalOrderCount()}, the all-time submission counter, which climbs
 * forever whether the collection is bounded or not. The canary for the defect
 * behind incident #1 could not have detected that defect. The retained gauge
 * below is the number that actually stops climbing once a bound exists, and it
 * ships next to the limit so the ratio is readable without hard-coding 10,000
 * into a query.
 */
public final class EngineMetrics {

    private final TradingEngine engine;

    public EngineMetrics(TradingEngine engine) {
        this.engine = engine;
    }

    /** Renders current engine state in the Prometheus text exposition format. */
    public String render() {
        StringBuilder out = new StringBuilder(2048);

        gauge(out, "engine_running",
              "Order generator state (1 = RUNNING).",
              engine.isRunning() ? 1 : 0);

        counter(out, "engine_orders_generated_total",
                "Orders generated since process start.",
                engine.getTotalGenerated());

        counter(out, "engine_orders_submitted_total",
                "All-time submissions to the order book, duplicates included.",
                engine.getOrderBook().totalOrderCount());

        counter(out, "engine_orders_unique_total",
                "All-time distinct order ids. Exact regardless of eviction.",
                engine.getOrderBook().uniqueOrderCount());

        // The pair below is the canary for the incident #1 defect class.
        // Retained is bounded by design; if it ever tracks submitted instead of
        // flattening against the limit, the bound has been lost.
        gauge(out, "engine_orders_retained",
              "Order-book entries currently held. Bounded - see engine_orders_retention_limit.",
              engine.getOrderBook().retainedOrderCount());

        gauge(out, "engine_orders_retention_limit",
              "Configured retention ceiling for the order book.",
              engine.getOrderBook().getRetention());

        gauge(out, "engine_duplicate_order_ids",
              "Distinct order ids seen more than once, within the retained window.",
              engine.getOrderBook().duplicateOrderIdCount());

        counter(out, "engine_orders_buy_total",  "BUY orders generated.",  engine.getBuyCount());
        counter(out, "engine_orders_sell_total", "SELL orders generated.", engine.getSellCount());

        gauge(out, "engine_last_price", "Most recent trade price.", engine.getLastPrice().doubleValue());

        gauge(out, "engine_cache_size", "Entries in the order cache.",
              engine.getOrderCache().size());
        counter(out, "engine_cache_hits_total", "Order cache hits.",
                engine.getOrderCache().getHitCount());
        counter(out, "engine_cache_misses_total", "Order cache misses.",
                engine.getOrderCache().getMissCount());

        return out.toString();
    }

    private static void gauge(StringBuilder out, String name, String help, double v) {
        emit(out, name, "gauge", help, v);
    }

    private static void counter(StringBuilder out, String name, String help, double v) {
        emit(out, name, "counter", help, v);
    }

    private static void emit(StringBuilder out, String name, String type, String help, double v) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        out.append(name).append(' ').append(num(v)).append('\n');
    }

    /**
     * Formats a double without scientific notation.
     *
     * <p>Java renders 5186336.0 as "5.186336E6". Prometheus parses that, but
     * grep and awk during an incident do not. Plain decimal costs nothing.
     */
    private static String num(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return new java.math.BigDecimal(v)
                .round(new java.math.MathContext(12))
                .stripTrailingZeros()
                .toPlainString();
    }
}
