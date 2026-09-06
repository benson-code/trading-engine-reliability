package com.binance.trading.api;

import com.binance.trading.engine.DesiredState;
import com.binance.trading.engine.OrderBook;
import com.binance.trading.engine.OrderCache;
import com.binance.trading.engine.TradingEngine;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Incident #2, end to end at the API: what the operator asked for must be
 * recoverable by a process that shares no memory with the one that received
 * the request.
 */
class EngineIntentPersistenceTest {

    private TradingApiServer server;
    private TradingEngine engine;

    private TradingApiServer start(DesiredState desired) throws Exception {
        engine = new TradingEngine(new OrderBook(), new OrderCache(100), 50, 0.0);
        server = new TradingApiServer(0, engine, null, desired);   // port 0 → OS picks
        server.start();
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = server.getPort();
        return server;
    }

    @AfterEach
    void tearDown() {
        if (engine != null) engine.stop();
        if (server != null) server.stop();
    }

    @Test
    @DisplayName("POST /engine/start records RUNNING; a fresh reader sees it; /stop records STOPPED")
    void startAndStopPersistIntent(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("desired-state");
        start(DesiredState.at(f));

        given().post("/api/v1/engine/start").then().statusCode(200).body("status", equalTo("RUNNING"));
        assertTrue(engine.isRunning());
        // "restart": a new DesiredState with no shared memory
        assertEquals(Optional.of(true), DesiredState.at(f).load(), "RUNNING must be on disk");

        given().post("/api/v1/engine/stop").then().statusCode(200).body("status", equalTo("STOPPED"));
        assertFalse(engine.isRunning());
        assertEquals(Optional.of(false), DesiredState.at(f).load(), "an explicit STOP must be on disk too");
    }

    @Test
    @DisplayName("Engine's own stop() does NOT touch the file — only operator calls do")
    void lifecycleStopDoesNotOverwriteIntent(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("desired-state");
        start(DesiredState.at(f));
        given().post("/api/v1/engine/start").then().statusCode(200);

        // What Main's shutdown hook does on SIGTERM:
        engine.stop();

        assertEquals(Optional.of(true), DesiredState.at(f).load(),
            "a clean shutdown must not be recorded as the operator asking for STOPPED - "
            + "that would make the restart faithfully reproduce incident #2");
    }

    @Test
    @DisplayName("If intent cannot be persisted, /start refuses rather than starting silently")
    void startRefusesWhenIntentCannotBePersisted(@TempDir Path dir) throws Exception {
        Path notADir = dir.resolve("blocker");
        Files.writeString(notADir, "x");                       // a file where a directory is needed
        start(DesiredState.at(notADir.resolve("desired-state")));

        given().post("/api/v1/engine/start").then()
               .statusCode(500).body("error", equalTo("STATE_PERSIST_FAILED"));
        assertFalse(engine.isRunning(), "fail closed: do not start what a restart would silently stop");
    }

    @Test
    @DisplayName("Disabled persistence (tests, k6): behaviour unchanged, no files written")
    void disabledIsInert(@TempDir Path dir) throws Exception {
        start(DesiredState.disabled());
        given().post("/api/v1/engine/start").then().statusCode(200);
        given().post("/api/v1/engine/stop").then().statusCode(200);
        assertEquals(0, Files.list(dir).count());
    }

    @Test
    @DisplayName("getPort() reports the bound port, not the requested 0")
    void reportsBoundPort(@TempDir Path dir) throws Exception {
        start(DesiredState.disabled());
        assertTrue(server.getPort() > 0, "requested 0, got " + server.getPort());
    }
}
