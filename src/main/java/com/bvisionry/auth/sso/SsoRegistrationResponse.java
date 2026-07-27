package com.bvisionry.auth.sso;

import java.time.Instant;
import java.util.UUID;

/**
 * A registration as the platform admin surface sees it.
 *
 * <p>{@code oidcClientSecret} and {@code samlMetadata} are deliberately absent:
 * the secret is a credential that has no read path, and the metadata blob is a
 * multi-kilobyte XML document nobody reads in a list. Both are write-only through
 * {@link SsoRegistrationRequest}; {@code samlConfigured} says whether one is
 * present without shipping it.
 */
public record SsoRegistrationResponse(
        UUID id,
        String registrationId,
        UUID orgId,
        SsoRegistration.Protocol protocol,
        String emailDomain,
        String displayName,
        boolean enabled,
        boolean samlConfigured,
        String oidcIssuerUri,
        String oidcClientId,
        Instant createdAt,
        Instant updatedAt
) {
    public static SsoRegistrationResponse from(SsoRegistration registration) {
        return new SsoRegistrationResponse(
                registration.getId(),
                registration.getRegistrationId(),
                registration.getOrgId(),
                registration.getProtocol(),
                registration.getEmailDomain(),
                registration.getDisplayName(),
                registration.isEnabled(),
                registration.getSamlMetadata() != null && !registration.getSamlMetadata().isBlank(),
                registration.getOidcIssuerUri(),
                registration.getOidcClientId(),
                registration.getCreatedAt(),
                registration.getUpdatedAt());
    }
}
