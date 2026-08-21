package com.bvisionry.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A booking link that must be a Cal.com page: everything
 * {@link ValidExternalUrl} requires, plus {@code https} and a host that IS
 * {@code cal.com} or a subdomain of it.
 *
 * <p>Two closed decisions in one constraint. The policy record fixes the
 * provider ({@code calendar: INTEGRATE_CAL_COM} — we never build booking), and
 * pinning the host in code is what makes that decision enforceable rather than
 * aspirational. It also closes a phishing hole: this URL is published BY a
 * coach TO the founders who trust them, so a compromised or malicious coach
 * account with a free-text link is a credential-harvester delivery mechanism
 * wearing our chrome.
 *
 * <p>Null and blank pass, matching {@link ValidExternalUrl} — blank is how a
 * coach withdraws their link. Compose {@code @NotBlank} where a value is
 * required.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CalComBookingUrlValidator.class)
public @interface CalComBookingUrl {
    String message() default "Must be an https link to your Cal.com booking page (cal.com or a cal.com subdomain)";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
