package com.bvisionry.auth.sso;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Create/update payload for an enterprise SSO registration. SUPER_ADMIN only —
 * {@code emailDomain} is a claim the platform has verified out of band, and a
 * mis-verified domain IS cross-tenant account takeover, so there is no
 * self-serve shape of this request.
 */
public record SsoRegistrationRequest(
        /* Slug, because it becomes a URL path segment the customer types into their IdP. */
        @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$",
                message = "must be a lowercase slug (letters, digits and hyphens, 3-64 chars)")
        String registrationId,

        @NotNull UUID orgId,

        @NotNull SsoRegistration.Protocol protocol,

        /* Normalised by the service before storage; validated there, not here, because
           "is this an IDN-encodable domain" is not expressible as a regex worth reading. */
        @NotBlank @Size(max = 253) String emailDomain,

        @NotBlank @Size(max = 128) String displayName,

        boolean enabled,

        /* SAML: the IdP EntityDescriptor XML. Required when protocol = SAML. */
        String samlMetadata,

        /* OIDC: all three required when protocol = OIDC. */
        @Size(max = 512) String oidcIssuerUri,
        @Size(max = 255) String oidcClientId,
        @Size(max = 512) String oidcClientSecret
) {

    /**
     * Redacts {@code oidcClientSecret}.
     *
     * <p>A record's generated {@code toString()} prints every component, and this
     * one carries a live credential at the CUSTOMER's identity provider. Nothing
     * logs the request object today — but a validation-failure log line, a debug
     * statement or an exception message that interpolates it is a one-line change
     * away, and by then the secret is in a log aggregator nobody thinks of as
     * secret-bearing. Cheaper to make that impossible than to remember.
     */
    @Override
    public String toString() {
        return "SsoRegistrationRequest[registrationId=%s, orgId=%s, protocol=%s, emailDomain=%s, "
                .formatted(registrationId, orgId, protocol, emailDomain)
                + "displayName=%s, enabled=%s, samlMetadata=%s, oidcIssuerUri=%s, oidcClientId=%s, "
                        .formatted(displayName, enabled,
                                samlMetadata == null ? "null" : "<present>", oidcIssuerUri, oidcClientId)
                + "oidcClientSecret=" + (oidcClientSecret == null ? "null" : "<redacted>") + "]";
    }
}
