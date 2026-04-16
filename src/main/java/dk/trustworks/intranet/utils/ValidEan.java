package dk.trustworks.intranet.utils;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that the annotated string is a well-formed EAN-13
 * with a correct GS1 Modulo 10 check digit.
 *
 * <p>Null and blank values are considered valid (combine with
 * {@code @NotBlank} if the field is required).
 */
@Documented
@Constraint(validatedBy = EanValidator.Validator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidEan {
    String message() default "EAN must be exactly 13 digits and pass GS1 Modulo 10";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
