package com.gic.reliability;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Exact, bounded two-decimal amounts. Rounding is never implicit. */
public final class Money {
    public static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal MAX = new BigDecimal("999999999.99");
    private Money() { }

    public static BigDecimal amount(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.compareTo(MAX) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be between 0.01 and 999999999.99");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must have at most two fractional digits");
        }
    }
}
