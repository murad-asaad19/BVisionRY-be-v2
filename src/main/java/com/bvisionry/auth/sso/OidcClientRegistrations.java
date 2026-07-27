package com.bvisionry.auth.sso;

import com.bvisionry.common.crypto.SecretEncryptionService;
import com.bvisionry.config.FrontendUrls;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Database-backed {@link ClientRegistrationRepository} — the multi-tenant OIDC
 * seam, mirroring {@link SamlRelyingPartyRegistrations}.
 *
 * <p>{@link ClientRegistrations#fromIssuerLocation} performs OIDC discovery, so
 * the authorization, token and JWKS endpoints are whatever the issuer publishes
 * rather than three more columns to keep in sync. That JWKS URI is what
 * {@code OidcAuthorizationCodeAuthenticationProvider} verifies the id_token
 * signature against — the reason this half of the feature is worth having at all
 * next to the platform's hand-rolled Google flow.
 *
 * <p><strong>{@code email_verified} is deliberately not required.</strong> Azure
 * AD omits the claim entirely, so demanding it would reject a large share of the
 * enterprise market on a technicality. What substitutes for it is stronger than
 * the claim itself: the id_token's signature ties the assertion to an issuer the
 * platform registered by hand, and the email must fall inside a domain the
 * platform verified the customer owns. An IdP that could lie about
 * {@code email_verified} could equally lie about the address.
 */
@Component
@RequiredArgsConstructor
public class OidcClientRegistrations implements ClientRegistrationRepository {

    private static final Logger log = LoggerFactory.getLogger(OidcClientRegistrations.class);

    private final SsoRegistrationRepository repository;
    private final FrontendUrls frontendUrls;
    private final SecretEncryptionService secretCipher;

    /**
     * Discovery result per registration, invalidated by the row's own
     * {@code updatedAt}.
     *
     * <p>This is a MITIGATION, not an optimisation. The handshake is anonymous, so
     * without it every unauthenticated request to
     * {@code /oauth2/authorization/{id}} makes Spring probe up to three discovery
     * URIs at the customer's identity provider with 30-second connect/read
     * timeouts: a ~3x traffic amplifier pointed at a third party we do not own, and
     * a way to tie up a Tomcat worker for 30 seconds per request. Caching turns the
     * first hit into the only hit.
     *
     * <p>Bounded by the number of registrations: keyed on registrationId, so an
     * edit REPLACES the entry rather than adding one. {@code updatedAt} is what
     * makes an issuer/client-id change take effect on the next request without a
     * restart or an eviction hook.
     */
    private final Map<String, Discovered> discoveryCache = new ConcurrentHashMap<>();

    private record Discovered(Instant updatedAt, ClientRegistration registration) {
    }

    /**
     * The stored client secret, decrypted — the ONLY place that happens.
     *
     * <p>The unstamped branch is not a fallback for "decryption might fail": it is the
     * pre-{@code V155} shape, when this column held plaintext. Those rows are converted
     * once at startup by {@code SsoRegistrationService#encryptLegacyPlaintextSecrets},
     * so this covers the window between the web server accepting requests and that
     * runner finishing, plus a row inserted by direct SQL. It cannot mask a decryption
     * failure: a stamped value that will not decrypt throws, and the caller logs and
     * returns no registration rather than handshaking with a wrong secret.
     */
    private String clientSecretOf(SsoRegistration row) {
        String stored = row.getOidcClientSecret();
        return SecretEncryptionService.isVersioned(stored) ? secretCipher.decrypt(stored) : stored;
    }

    /** Browser-visible redirect_uri; the frontend relays it to the backend's redirection endpoint. */
    public static String redirectUri(FrontendUrls urls, String registrationId) {
        return urls.path("/auth/sso/enterprise/oauth2/callback/" + registrationId);
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        SsoRegistration row = repository.findByRegistrationIdAndEnabledTrue(registrationId)
                .filter(r -> r.getProtocol() == SsoRegistration.Protocol.OIDC)
                .orElse(null);
        if (row == null) {
            // Unknown, disabled, or a SAML row reached through the OIDC path. Drop any
            // cached registration so disabling a row takes effect immediately rather
            // than lasting until its next edit.
            discoveryCache.remove(registrationId);
            return null;
        }

        Discovered cached = discoveryCache.get(registrationId);
        if (cached != null && cached.updatedAt().equals(row.getUpdatedAt())) {
            return cached.registration();
        }

        try {
            ClientRegistration built = ClientRegistrations.fromIssuerLocation(row.getOidcIssuerUri())
                    .registrationId(registrationId)
                    .clientId(row.getOidcClientId())
                    .clientSecret(clientSecretOf(row))
                    .redirectUri(redirectUri(frontendUrls, registrationId))
                    .scope("openid", "email", "profile")
                    .clientName(row.getDisplayName())
                    .build();
            discoveryCache.put(registrationId, new Discovered(row.getUpdatedAt(), built));
            return built;
        } catch (RuntimeException notUsable) {
            // Deliberately NOT cached: a transient outage at the IdP — or an encryption
            // key that has been rotated out from under the stored secret — must not pin
            // a failure until someone edits the row. The message is the cause's own and
            // never the secret; SecretEncryptionService reports the key VERSION only.
            log.error("Could not build the OIDC client registration for {} at {}: {}",
                    registrationId, row.getOidcIssuerUri(), notUsable.getMessage());
            return null;
        }
    }
}
