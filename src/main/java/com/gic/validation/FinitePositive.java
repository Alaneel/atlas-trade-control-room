package com.gic.validation;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Rejects zero, negative values, NaN, and infinity without imposing a desk ceiling. */
@Documented
@Constraint(validatedBy = FinitePositiveValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface FinitePositive {
    String message() default "Amount must be finite and greater than zero";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
