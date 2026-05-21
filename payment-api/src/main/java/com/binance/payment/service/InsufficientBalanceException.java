package com.binance.payment.service;

/**
 * Thrown by a {@link PaymentRepository} when the requested debit cannot be
 * satisfied because the account exists but lacks sufficient balance.
 *
 * <p>Extends {@link IllegalStateException} so that existing {@code catch}
 * sites that treat it as a generic state issue still work, while the API
 * layer can match it specifically to surface {@code 402 Payment Required}
 * (versus {@code 500} for unrelated state failures).</p>
 */
public class InsufficientBalanceException extends IllegalStateException {
    public InsufficientBalanceException(String message) {
        super(message);
    }
}
