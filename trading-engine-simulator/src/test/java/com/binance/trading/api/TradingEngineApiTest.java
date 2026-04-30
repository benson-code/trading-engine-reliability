package com.binance.trading.api;

import com.binance.trading.engine.OrderBook;
import com.binance.trading.engine.OrderCache;
import com.binance.trading.engine.TradingEngine;
import io.qameta.allure.*;
import java.net.ServerSocket;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * API layer tests: RestAssured against a live embedded TradingEngine + HttpServer.
 * Tests the full HTTP stack (not WireMock — a real engine is running).
 *
 * Port 8092, engine interval=50ms, duplicate probability=10%
 * @BeforeAll starts the engine and waits 1 second (~20 orders generated)
 */
@Epic("Trading Engine")
@Feature("API Layer — RestAssured Against Live Engine")
class TradingEngineApiTest {

    private static TradingEngine     engine;
    private static TradingApiServer  server;
    private static int               PORT;

    // BUG-02 fix: use a random free port so tests don't conflict with the production server
    private static int findFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    @BeforeAll
    static void startEngine() throws Exception {
        PORT = findFreePort();
        OrderBook  orderBook  = new OrderBook();
        OrderCache orderCache = new OrderCache(1000);
        engine = new TradingEngine(orderBook, orderCache, 50, 0.1); // 10% dup rate for testing
        server = new TradingApiServer(PORT, engine);
        server.start();
        engine.start();

        RestAssured.baseURI = "http://localhost";
        RestAssured.port    = PORT;

        Thread.sleep(1_000); // wait 1 second → ~20 orders generated
    }

    @AfterAll
    static void stopEngine() {
        if (engine != null) engine.stop();
        if (server != null) server.stop();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  GET /api/v1/status
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Engine Status")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("GET /api/v1/status → 200 + status=RUNNING with live order counts")
    void statusEndpoint_returnsRunningWithCounts() {
        given()
        .when()
            .get("/api/v1/status")
        .then()
            .statusCode(200)
            .body("status",          equalTo("RUNNING"))
            .body("total_generated", greaterThan(0))
            .body("unique_orders",   greaterThanOrEqualTo(1))
            .body("cache_size",      greaterThanOrEqualTo(1))
            .body("has_duplicates",  notNullValue());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  GET /api/v1/orders
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Get All Orders")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("GET /api/v1/orders → 200 + order list with total/unique/duplicate counts")
    void getAllOrders_returnsOrderListWithMetadata() {
        given()
        .when()
            .get("/api/v1/orders")
        .then()
            .statusCode(200)
            .body("total_count",  greaterThan(0))
            .body("unique_count", greaterThanOrEqualTo(1))
            .body("orders",       not(empty()));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  POST /api/v1/orders
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Create New Order — 201")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("POST /api/v1/orders with new order_id → 201 Created + is_new=true")
    void postOrder_newOrderId_returns201() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {
                        "order_id": "ORD-API-TEST-NEW-001",
                        "type": "BUY",
                        "symbol": "BTCUSDT",
                        "amount": "0.50000000",
                        "price": 95000.00,
                        "status": "PENDING",
                        "timestamp": 1714348800000
                    }
                    """)
        .when()
            .post("/api/v1/orders")
        .then()
            .statusCode(201)
            .body("is_new",   equalTo(true))
            .body("order_id", equalTo("ORD-API-TEST-NEW-001"))
            .body("message",  containsString("created"));
    }

    @Test
    @Story("Duplicate Order — 200")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("POST /api/v1/orders with duplicate order_id → 200 (not 201) + is_new=false")
    void postOrder_duplicateOrderId_returns200() {
        String body = """
                {
                    "order_id": "ORD-API-DUP-TEST-001",
                    "type":     "BUY",
                    "symbol":   "BTCUSDT",
                    "amount":   "0.10000000",
                    "price":    95000.00,
                    "status":   "PENDING",
                    "timestamp": 1714348800000
                }
                """;

        // First submission → 201 Created
        given().contentType(ContentType.JSON).body(body)
            .when().post("/api/v1/orders")
            .then().statusCode(201).body("is_new", equalTo(true));

        // Duplicate submission → 200 OK + is_new=false
        given().contentType(ContentType.JSON).body(body)
            .when().post("/api/v1/orders")
            .then()
                .statusCode(200)
                .body("is_new",  equalTo(false))
                .body("message", containsString("Duplicate"));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  GET /api/v1/orders/{id}
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Get Order By ID — Cache Hit")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("GET /api/v1/orders/{id} → 200 for an order that exists in the engine")
    void getOrderById_returns200_forExistingOrder() {
        // Insert a known order first
        given().contentType(ContentType.JSON)
            .body("""
                    {
                        "order_id": "ORD-LOOKUP-001",
                        "type":     "SELL",
                        "symbol":   "BTCUSDT",
                        "amount":   "0.25000000",
                        "price":    95000.00,
                        "status":   "PENDING",
                        "timestamp": 1714348800000
                    }
                    """)
            .when().post("/api/v1/orders").then().statusCode(201);

        // Now look it up
        given()
        .when()
            .get("/api/v1/orders/ORD-LOOKUP-001")
        .then()
            .statusCode(200)
            .body("order_id", equalTo("ORD-LOOKUP-001"))
            .body("type",     equalTo("SELL"));
    }

    @Test
    @Story("Get Order By ID — 404")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("GET /api/v1/orders/{id} → 404 for a non-existent order_id")
    void getOrderById_returns404_forNonExistentId() {
        given()
        .when()
            .get("/api/v1/orders/NON-EXISTENT-ORDER-ID-999")
        .then()
            .statusCode(404)
            .body("error", containsString("not found"));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  GET /api/v1/orders/duplicates
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Duplicate Detection Endpoint")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("GET /api/v1/orders/duplicates → 200 + duplicate_count + frequency_map")
    void duplicatesEndpoint_returnsAnalysisResult() {
        given()
        .when()
            .get("/api/v1/orders/duplicates")
        .then()
            .statusCode(200)
            .body("has_duplicates",    notNullValue())
            .body("duplicate_count",   greaterThanOrEqualTo(0))
            .body("duplicate_order_ids", notNullValue())
            .body("frequency_map",     notNullValue());
    }
}
