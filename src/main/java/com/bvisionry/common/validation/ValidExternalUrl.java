package com.bvisionry.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a String is a safe external URL:
 *   - http or https scheme
 *   - non-empty host
 *   - not a loopback / private / link-local host (basic SSRF-avoidance)
 * Null and blank values pass (use @NotBlank separately if the field is required).
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidExternalUrlValidator.class)
public @interface ValidExternalUrl {
    String message() default "Must be a valid http(s) URL pointing to a public host";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
