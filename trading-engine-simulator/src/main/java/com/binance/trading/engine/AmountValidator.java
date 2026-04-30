package com.binance.trading.engine;

import com.binance.trading.model.ValidationResult;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Maps to LC-65 (Valid Number) + LC-8 (atoi) — string-based amount validation
 * with Bitcoin-specific rules: max 8 decimal places, range 0.00000001 ~ 999999999.99999999
 */
public class AmountValidator {

    // Positive number, 1-9 digits before dot, 1-8 digits after (optional dot)
    private static final Pattern AMOUNT_PATTERN =
        Pattern.compile("^\\d{1,9}(\\.\\d{1,8})?$");

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99999999");
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.00000001"); // 1 satoshi

    public static boolean isValid(String amount) {
        return validate(amount).isValid();
    }

    public static ValidationResult validate(String amount) {
        if (amount == null || amount.isBlank()) {
            return ValidationResult.error("Amount must not be null or blank");
        }
        if (!AMOUNT_PATTERN.matcher(amount).matches()) {
            return ValidationResult.error(
                "Amount must be a positive number with max 8 decimal places: [" + amount + "]");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(amount);
        } catch (NumberFormatException e) {
            return ValidationResult.error("Amount is not a valid number: [" + amount + "]");
        }
        if (value.compareTo(MIN_AMOUNT) < 0) {
            return ValidationResult.error(
                "Amount must be >= 0.00000001 (1 satoshi), got: [" + amount + "]");
        }
        if (value.compareTo(MAX_AMOUNT) > 0) {
            return ValidationResult.error(
                "Amount exceeds maximum 999999999.99999999, got: [" + amount + "]");
        }
        return ValidationResult.ok();
    }
}
