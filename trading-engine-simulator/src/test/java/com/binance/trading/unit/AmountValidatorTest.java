package com.binance.trading.unit;

import com.binance.trading.engine.AmountValidator;
import com.binance.trading.model.ValidationResult;
import io.qameta.allure.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pattern 3 — String-based amount validation.
 * Maps to LC-65 (Valid Number) + LC-8 (String to Integer / atoi).
 *
 * Bitcoin-specific rules:
 *   - Must be a positive number
 *   - Max 8 decimal places (1 satoshi = 0.00000001 BTC)
 *   - Range: 0.00000001 ~ 999999999.99999999
 *   - No scientific notation (1e5), no commas (1,000), no trailing dot (100.)
 */
@Epic("Trading Engine")
@Feature("Pattern 3 — Amount Validation: String Processing (LC-65 / LC-8)")
class AmountValidatorTest {

    // ════════════════════════════════════════════════════════════════════════
    //  Valid Amounts — @ParameterizedTest
    // ════════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "[VALID] \"{0}\"")
    @CsvSource({
        "0.00000001",          // 1 satoshi — minimum allowed
        "0.10000000",          // 0.1 BTC
        "1.00000000",          // 1 BTC
        "100",                 // integer with no decimal part
        "100.5",               // 1 decimal place
        "99999999.99999999",   // just under maximum
        "0.12345678",          // exactly 8 decimal places (Bitcoin max precision)
        "1",                   // single digit
        "999999999",           // large integer, no decimal
    })
    @Story("LC-65 — Valid Number")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("[LC-65] isValid() returns true for valid Bitcoin trade amounts")
    void isValid_trueForValidAmounts(String amount) {
        assertTrue(AmountValidator.isValid(amount),
            "Expected [" + amount + "] to be VALID but isValid() returned false");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Invalid Amounts — @ParameterizedTest
    // ════════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "[INVALID] \"{0}\" — {1}")
    @CsvSource({
        "0,               zero is below minimum (1 satoshi)",
        "-100.00,         negative amounts not allowed",
        "-0.00000001,     negative satoshi",
        "abc,             non-numeric string",
        "100.123456789,   9 decimal places exceeds Bitcoin precision of 8",
        "1000000000.0,    10 digits before decimal exceeds max",
        "1e5,              scientific notation not allowed",
        "'1,000.00',      comma as thousands separator not allowed",
        "100.,             trailing dot without digits",
        ".5,               leading dot without integer part",
        "' 100',           leading space not allowed",
        "'100 ',           trailing space not allowed",
    })
    @Story("LC-65 — Invalid Number")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("[LC-65] isValid() returns false for invalid amounts")
    void isValid_falseForInvalidAmounts(String amount, String reason) {
        assertFalse(AmountValidator.isValid(amount),
            "Expected [" + amount + "] to be INVALID (" + reason + ") but isValid() returned true");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Null / Blank Input
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Null / Blank Guard")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("validate(null) returns error — not NullPointerException")
    void validate_returnsError_forNullInput() {
        ValidationResult result = AmountValidator.validate(null);

        assertFalse(result.isValid(),                    "null must be invalid");
        assertNotNull(result.getErrorMessage(),           "error message must be provided");
        assertTrue(result.getErrorMessage().contains("null") ||
                   result.getErrorMessage().contains("blank"),
            "Error message must mention null/blank: " + result.getErrorMessage());
    }

    @Test
    @Story("Null / Blank Guard")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("validate(\"\") returns error — blank string")
    void validate_returnsError_forBlankString() {
        ValidationResult result = AmountValidator.validate("");
        assertFalse(result.isValid(), "Blank string must be invalid");
        assertNotNull(result.getErrorMessage());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Boundary Cases
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Story("Boundary — Minimum (1 Satoshi)")
    @Severity(SeverityLevel.CRITICAL)
    @DisplayName("validate(\"0.00000001\") → valid (exactly 1 satoshi = minimum)")
    void validate_returnsOk_forMinimumSatoshi() {
        ValidationResult result = AmountValidator.validate("0.00000001");
        assertTrue(result.isValid(),         "1 satoshi must be valid");
        assertNull(result.getErrorMessage(), "No error message for valid input");
    }

    @Test
    @Story("Boundary — Just Below Minimum")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("validate(\"0.000000009\") → invalid (9 decimal places, below 1 satoshi)")
    void validate_returnsError_belowMinimumSatoshi() {
        ValidationResult result = AmountValidator.validate("0.000000009");
        assertFalse(result.isValid(), "Below 1 satoshi must be invalid");
    }

    @Test
    @Story("Boundary — 8 vs 9 Decimal Places")
    @Severity(SeverityLevel.NORMAL)
    @DisplayName("8 decimal places → valid; 9 decimal places → invalid")
    void decimalPrecision_boundary() {
        assertTrue(AmountValidator.isValid("0.12345678"),  "8 decimals must be valid");
        assertFalse(AmountValidator.isValid("0.123456789"), "9 decimals must be invalid");
    }
}
