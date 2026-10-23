package com.gic.validation;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import java.math.BigDecimal;

public class FinitePositiveValidator implements ConstraintValidator<FinitePositive, BigDecimal> {
    @Override
    public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
        // Requiredness is handled by @NotNull so callers receive a precise error.
        return value == null || MoneyAmounts.isValidPositive(value);
    }
}
