package com.bvisionry.auth.sso;

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
                    .clientSecret(row.getOidcClientSecret())
                    .redirectUri(redirectUri(frontendUrls, registrationId))
                    .scope("openid", "email", "profile")
                    .clientName(row.getDisplayName())
                    .build();
            discoveryCache.put(registrationId, new Discovered(row.getUpdatedAt(), built));
            return built;
        } catch (RuntimeException unreachableIssuer) {
            // Deliberately NOT cached: a transient outage at the IdP must not pin a
            // failure until someone edits the row.
            log.error("OIDC discovery failed for registration {} at {}: {}",
                    registrationId, row.getOidcIssuerUri(), unreachableIssuer.getMessage());
            return null;
        }
    }
}
