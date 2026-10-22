package com.gic.validation;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

public class FinitePositiveValidator implements ConstraintValidator<FinitePositive, Double> {
    @Override
    public boolean isValid(Double value, ConstraintValidatorContext context) {
        // Requiredness is handled by @NotNull so callers receive a precise error.
        return value == null || (Double.isFinite(value) && value > 0);
    }
}
