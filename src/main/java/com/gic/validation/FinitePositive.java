package com.gic.validation;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Positive exact decimal with a range that fits MongoDB Decimal128. */
@Documented
@Constraint(validatedBy = FinitePositiveValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface FinitePositive {
    String message() default "Amount must be positive with at most 26 integral and 8 fractional digits";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
