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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

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
     * Discovery result per registration — BOTH outcomes — invalidated by the row's
     * own {@code updatedAt}.
     *
     * <p>This is a MITIGATION, not an optimisation. The handshake is anonymous, so
     * without it every unauthenticated request to
     * {@code /oauth2/authorization/{id}} makes Spring probe up to three discovery
     * URIs at the customer's identity provider with 30-second connect/read
     * timeouts: a ~3x traffic amplifier pointed at a third party we do not own, and
     * a way to tie up a Tomcat worker for 30 seconds per request. Caching turns the
     * first hit into the only hit — and that must hold while the IdP is DOWN too,
     * or an outage turns every request back into a 30-second probe. A FAILURE is
     * therefore cached as well, but only for {@link #FAILURE_TTL}, so a transient
     * outage (or a rotated encryption key) is never pinned until someone edits the
     * row; and the probe itself is single-flighted per registration, so at most one
     * worker is ever parked on a given IdP. This is what makes
     * {@code SsoHandshakeRateLimitFilter}'s exemption of the handshake hops safe.
     *
     * <p>Bounded by the number of registrations: keyed on registrationId, so an
     * edit REPLACES the entry rather than adding one. {@code updatedAt} is what
     * makes an issuer/client-id change take effect on the next request without a
     * restart or an eviction hook.
     */
    private final Map<String, Discovered> discoveryCache = new ConcurrentHashMap<>();

    /** One lock per registration: the single-flight gate around the outbound probe. */
    private final Map<String, ReentrantLock> probes = new ConcurrentHashMap<>();

    /** How long a FAILED probe is believed before the issuer is tried again. */
    private static final Duration FAILURE_TTL = Duration.ofSeconds(30);

    private Clock clock = Clock.systemUTC();

    /** Test seam, mirroring {@code RateLimitService}. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** {@code registration} is null for a cached FAILURE; {@code probedAt} bounds its life. */
    private record Discovered(Instant updatedAt, Instant probedAt, ClientRegistration registration) {
    }

    /** Is this cache entry still the answer for {@code row}, at {@code now}? */
    private static boolean usable(Discovered cached, SsoRegistration row, Instant now) {
        return cached != null
                && Objects.equals(cached.updatedAt(), row.getUpdatedAt())
                && (cached.registration() != null
                        || cached.probedAt().isAfter(now.minus(FAILURE_TTL)));
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
            probes.remove(registrationId);
            return null;
        }

        Instant now = Instant.now(clock);
        Discovered cached = discoveryCache.get(registrationId);
        if (usable(cached, row, now)) {
            return cached.registration();
        }

        // ponytail: tryLock, not lock — during a cold start concurrent callers get a
        // null (login-page error, self-healing on retry) instead of queueing on the
        // IdP round trip; wait with a short timeout if cold-start contention bites.
        ReentrantLock probe = probes.computeIfAbsent(registrationId, k -> new ReentrantLock());
        if (!probe.tryLock()) {
            log.warn("OIDC discovery for {} already in flight — answering null rather than queueing", registrationId);
            return null;
        }
        try {
            // The winner may have just filled the cache while this caller raced it.
            cached = discoveryCache.get(registrationId);
            if (usable(cached, row, now)) {
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
                discoveryCache.put(registrationId, new Discovered(row.getUpdatedAt(), now, built));
                return built;
            } catch (RuntimeException notUsable) {
                // Cached briefly (FAILURE_TTL): a transient outage at the IdP — or an
                // encryption key rotated out from under the stored secret — must not pin
                // a failure until someone edits the row, but it also must not be re-probed
                // by every anonymous request while it lasts. The message is the cause's
                // own and never the secret; SecretEncryptionService reports the key
                // VERSION only.
                log.error("Could not build the OIDC client registration for {} at {}: {}",
                        registrationId, row.getOidcIssuerUri(), notUsable.getMessage());
                discoveryCache.put(registrationId, new Discovered(row.getUpdatedAt(), now, null));
                return null;
            }
        } finally {
            probe.unlock();
        }
    }
}
