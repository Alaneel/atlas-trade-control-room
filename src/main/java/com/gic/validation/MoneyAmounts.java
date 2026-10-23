package com.gic.validation;

import com.gic.exception.BusinessException;
import java.math.BigDecimal;

/** Exact decimal contract shared by HTTP validation, persistence, and decisions. */
public final class MoneyAmounts {
    public static final BigDecimal MAX_VALUE = new BigDecimal("99999999999999999999999999.99999999");

    private MoneyAmounts() {}

    public static boolean isValidPositive(BigDecimal value) {
        return value != null && value.signum() > 0 && withinPrecision(value);
    }

    public static BigDecimal requirePositive(BigDecimal value) {
        if (!isValidPositive(value)) {
            throw new BusinessException("INVALID_TRADE_AMOUNT",
                    "Amount must be positive with at most 26 integral and 8 fractional digits");
        }
        return canonical(value);
    }

    public static BigDecimal requireBalance(BigDecimal value) {
        if (value == null || value.signum() < 0 || !withinPrecision(value)) {
            throw new BusinessException("INVALID_LIMIT_BALANCE",
                    "Stored balance cannot be represented exactly within 26 integral and 8 fractional digits");
        }
        return canonical(value);
    }

    private static boolean withinPrecision(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        long integralDigits = (long) normalized.precision() - normalized.scale();
        return normalized.scale() <= 8 && integralDigits <= 26;
    }

    private static BigDecimal canonical(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }
}
