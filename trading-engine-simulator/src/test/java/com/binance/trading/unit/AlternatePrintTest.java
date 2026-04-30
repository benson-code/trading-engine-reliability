package com.binance.trading.unit;

import io.qameta.allure.*;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pattern 4 — Two threads alternating using Semaphores.
 * Maps to LC-1115 (Print FooBar Alternately).
 *
 * Trading engine variant: BUY-THREAD and SELL-THREAD must alternate strictly.
 * This is the exact pattern the TradingEngine uses internally.
 *
 * Semaphore handoff mechanism:
 *   BUY-THREAD:  acquire(buySem) → do work → release(sellSem)
 *   SELL-THREAD: acquire(sellSem)→ do work → release(buySem)
 *   → guarantees BUY→SELL→BUY→SELL even under concurrent execution
 */
@Epic("Trading Engine")
@Feature("Pattern 4 — Multi-threading: Two Threads Alternating (LC-1115)")
class AlternatePrintTest {

    // ════════════════════════════════════════════════════════════════════════
    //  LC-1115: Strict Alternation
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("LC-1115 — Strict Alternation")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("[LC-1115] BUY→SELL→BUY→SELL... pattern maintained across 10 iterations")
    void buyAndSellThreadsAlternate_strictly() throws InterruptedException {
        List<String> sequence   = Collections.synchronizedList(new ArrayList<>());
        Semaphore buySemaphore  = new Semaphore(1); // BUY goes first
        Semaphore sellSemaphore = new Semaphore(0); // SELL waits
        int iterations = 10;

        Thread buyThread = new Thread(() -> {
            for (int i = 1; i <= iterations; i++) {
                try {
                    buySemaphore.acquire();
                    sequence.add("BUY-" + i);
                    sellSemaphore.release();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "BUY-THREAD");

        Thread sellThread = new Thread(() -> {
            for (int i = 1; i <= iterations; i++) {
                try {
                    sellSemaphore.acquire();
                    sequence.add("SELL-" + i);
                    buySemaphore.release();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "SELL-THREAD");

        buyThread.start();
        sellThread.start();
        buyThread.join(5_000);
        sellThread.join(5_000);

        // Verify total count
        assertEquals(iterations * 2, sequence.size(),
            "Must produce exactly " + (iterations * 2) + " items");

        // Verify strict BUY→SELL alternation at every position
        for (int i = 0; i < sequence.size(); i++) {
            if (i % 2 == 0) {
                assertTrue(sequence.get(i).startsWith("BUY"),
                    "Even index " + i + " must be BUY, got: " + sequence.get(i));
            } else {
                assertTrue(sequence.get(i).startsWith("SELL"),
                    "Odd index " + i + " must be SELL, got: " + sequence.get(i));
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Deadlock Safety
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("LC-1115 — No Deadlock")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("[LC-1115] Both threads complete 50 iterations without deadlock (timeout 5s)")
    void bothThreadsComplete_withoutDeadlock() throws InterruptedException {
        Semaphore buySemaphore  = new Semaphore(1);
        Semaphore sellSemaphore = new Semaphore(0);
        int iterations = 50;
        int[] buyCount  = {0};
        int[] sellCount = {0};

        Thread buyThread = new Thread(() -> {
            for (int i = 0; i < iterations; i++) {
                try {
                    buySemaphore.acquire();
                    buyCount[0]++;
                    sellSemaphore.release();
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }, "BUY-THREAD");

        Thread sellThread = new Thread(() -> {
            for (int i = 0; i < iterations; i++) {
                try {
                    sellSemaphore.acquire();
                    sellCount[0]++;
                    buySemaphore.release();
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }, "SELL-THREAD");

        buyThread.start();
        sellThread.start();
        buyThread.join(5_000);
        sellThread.join(5_000);

        assertFalse(buyThread.isAlive(),   "BUY-THREAD must finish within 5 seconds (no deadlock)");
        assertFalse(sellThread.isAlive(),  "SELL-THREAD must finish within 5 seconds (no deadlock)");
        assertEquals(iterations, buyCount[0],  "BUY must complete " + iterations + " iterations");
        assertEquals(iterations, sellCount[0], "SELL must complete " + iterations + " iterations");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  No Data Race — Sequence Integrity
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("LC-1115 — No Data Race")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("[LC-1115] BUY and SELL sequences are both in-order — no data race corruption")
    void sequenceIsInOrder_noDataRace() throws InterruptedException {
        List<String> sequence   = Collections.synchronizedList(new ArrayList<>());
        Semaphore buySemaphore  = new Semaphore(1);
        Semaphore sellSemaphore = new Semaphore(0);
        int iterations = 20;

        Thread buyThread = new Thread(() -> {
            for (int i = 1; i <= iterations; i++) {
                try {
                    buySemaphore.acquire();
                    sequence.add(String.format("BUY-%02d", i));
                    sellSemaphore.release();
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }, "BUY-THREAD");

        Thread sellThread = new Thread(() -> {
            for (int i = 1; i <= iterations; i++) {
                try {
                    sellSemaphore.acquire();
                    sequence.add(String.format("SELL-%02d", i));
                    buySemaphore.release();
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }, "SELL-THREAD");

        buyThread.start();
        sellThread.start();
        buyThread.join(5_000);
        sellThread.join(5_000);

        // Verify each sub-sequence is in monotonic order (no data race reordering)
        int buyNum = 0, sellNum = 0;
        for (String entry : sequence) {
            if (entry.startsWith("BUY")) {
                buyNum++;
                assertEquals(String.format("BUY-%02d", buyNum), entry,
                    "BUY entries must be in order, got: " + entry);
            } else {
                sellNum++;
                assertEquals(String.format("SELL-%02d", sellNum), entry,
                    "SELL entries must be in order, got: " + entry);
            }
        }
        assertEquals(iterations, buyNum,  "Must have " + iterations + " BUY entries");
        assertEquals(iterations, sellNum, "Must have " + iterations + " SELL entries");
    }
}
