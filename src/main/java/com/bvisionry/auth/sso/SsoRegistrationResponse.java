package com.bvisionry.auth.sso;

import java.time.Instant;
import java.util.UUID;

/**
 * A registration as the platform admin surface sees it.
 *
 * <p>{@code oidcClientSecret} and {@code samlMetadata} are deliberately absent:
 * the secret is a credential that has no read path, and the metadata blob is a
 * multi-kilobyte XML document nobody reads in a list. Both are write-only through
 * {@link SsoRegistrationRequest}; {@code samlConfigured} and
 * {@code oidcClientSecretConfigured} say whether one is present without shipping
 * it.
 *
 * <p>{@code oidcClientSecretConfigured} is what makes the admin console's edit
 * form honest. Blank means "keep the stored value" on update, so the form has to
 * tell the operator whether there IS one to keep — and it cannot infer that from
 * {@code protocol == OIDC}, because a SAML registration being switched to OIDC is
 * an OIDC row with no stored secret and must be made to supply one. A boolean is
 * the whole of what may be said: any masked or truncated echo of the ciphertext
 * would be a read path for a credential that has none.
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
        boolean oidcClientSecretConfigured,
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
                registration.getOidcClientSecret() != null && !registration.getOidcClientSecret().isBlank(),
                registration.getOidcIssuerUri(),
                registration.getOidcClientId(),
                registration.getCreatedAt(),
                registration.getUpdatedAt());
    }
}
