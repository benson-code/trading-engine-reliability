package com.binance.payment.service;

/**
 * Thrown by a {@link PaymentRepository} when a payment's currency does not
 * match the currency the target account holds (e.g. a BTC payment against a
 * USDT account).
 *
 * <p>The request is well-formed and the account exists — it simply cannot be
 * processed as asked — so the API surfaces this as {@code 422 Unprocessable
 * Entity} rather than {@code 400} (malformed) or {@code 409} (state conflict).</p>
 */
public class CurrencyMismatchException extends RuntimeException {
    public CurrencyMismatchException(String message) {
        super(message);
    }
}
