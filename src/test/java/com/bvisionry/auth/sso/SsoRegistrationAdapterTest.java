package com.bvisionry.auth.sso;

import com.bvisionry.common.crypto.SecretEncryptionService;
import com.bvisionry.config.FrontendProperties;
import com.bvisionry.config.FrontendUrls;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The two database-backed registration repositories: the seam between a row and
 * the objects Spring Security's SAML / OIDC machinery consumes.
 *
 * <p>What this pins is the wiring the identity provider is configured against —
 * the ACS location and the OIDC {@code redirect_uri}. Both are built from the
 * FRONTEND origin, because every hop of this handshake is relayed through the web
 * app (the API's cookies are host-only and in production the two are different
 * eTLD+1). Get either wrong and the customer's IdP posts the assertion at a URL
 * that does not exist, which is a failure nobody sees until an enterprise
 * customer tries to log in.
 *
 * <p>It also pins fail-CLOSED on every degraded input: a row of the wrong
 * protocol, a disabled row, unparseable metadata, an unreachable issuer. Each
 * must answer "no such registration" so the handshake dies at the login page
 * rather than surfacing a stack trace or, worse, half a registration.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SsoRegistrationAdapterTest {

    private static final String FRONTEND = "https://app.bvisionry.test";
    private static final String REGISTRATION = "orgb-idp";

    @Mock private SsoRegistrationRepository repository;

    /** Real cipher, not a mock: the point of these tests is what actually reaches the IdP. */
    private static final SecretEncryptionService CIPHER = new SecretEncryptionService(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

    private final FrontendUrls frontendUrls = frontendUrls();

    private static FrontendUrls frontendUrls() {
        FrontendProperties properties = new FrontendProperties();
        properties.setBaseUrl(FRONTEND);
        return new FrontendUrls(properties);
    }

    private SsoRegistration row(SsoRegistration.Protocol protocol) {
        SsoRegistration registration = new SsoRegistration();
        registration.setId(UUID.randomUUID());
        registration.setRegistrationId(REGISTRATION);
        registration.setOrgId(UUID.randomUUID());
        registration.setProtocol(protocol);
        registration.setEmailDomain("orgb.com");
        registration.setDisplayName("OrgB IdP");
        registration.setEnabled(true);
        return registration;
    }

    private void stored(SsoRegistration registration) {
        when(repository.findByRegistrationIdAndEnabledTrue(REGISTRATION))
                .thenReturn(Optional.ofNullable(registration));
    }

    // ------------------------------------------------------------------- SAML

    @Test
    void samlMetadataFromTheRowBecomesARelyingPartyRegistration() {
        SsoRegistration registration = row(SsoRegistration.Protocol.SAML);
        registration.setSamlMetadata(SsoTestFixtures.IDP_METADATA);
        stored(registration);

        RelyingPartyRegistration relyingParty =
                new SamlRelyingPartyRegistrations(repository, frontendUrls).findByRegistrationId(REGISTRATION);

        assertThat(relyingParty).isNotNull();
        assertThat(relyingParty.getRegistrationId()).isEqualTo(REGISTRATION);
        assertThat(relyingParty.getEntityId()).isEqualTo(FRONTEND + "/saml2/sp/" + REGISTRATION);
        // The URL the customer pastes into their IdP. Frontend origin, because the
        // web app relays the assertion POST to the backend.
        assertThat(relyingParty.getAssertionConsumerServiceLocation())
                .isEqualTo(FRONTEND + "/auth/sso/enterprise/saml2/acs/" + REGISTRATION);
        // The trust anchor came out of the row, not out of configuration.
        assertThat(relyingParty.getAssertingPartyMetadata().getEntityId())
                .isEqualTo("https://idp.orgb.test/entity");
        assertThat(relyingParty.getAssertingPartyMetadata().getVerificationX509Credentials()).isNotEmpty();
    }

    @Test
    void unparseableSamlMetadataResolvesToNoRegistration() {
        SsoRegistration registration = row(SsoRegistration.Protocol.SAML);
        registration.setSamlMetadata("<not-metadata/>");
        stored(registration);

        assertThat(new SamlRelyingPartyRegistrations(repository, frontendUrls)
                .findByRegistrationId(REGISTRATION)).isNull();
    }

    @Test
    void anOidcRowIsNotServedBySamlAndViceVersa() {
        // Protocol confusion would let a row configured for one protocol be driven
        // by the other's filter with whatever half-populated state it could build.
        stored(row(SsoRegistration.Protocol.OIDC));
        assertThat(new SamlRelyingPartyRegistrations(repository, frontendUrls)
                .findByRegistrationId(REGISTRATION)).isNull();

        stored(row(SsoRegistration.Protocol.SAML));
        assertThat(new OidcClientRegistrations(repository, frontendUrls, CIPHER)
                .findByRegistrationId(REGISTRATION)).isNull();
    }

    @Test
    void anUnknownOrDisabledRegistrationResolvesToNothingOnBothProtocols() {
        stored(null);
        assertThat(new SamlRelyingPartyRegistrations(repository, frontendUrls)
                .findByRegistrationId(REGISTRATION)).isNull();
        assertThat(new OidcClientRegistrations(repository, frontendUrls, CIPHER)
                .findByRegistrationId(REGISTRATION)).isNull();
    }

    // ------------------------------------------------------------------- OIDC

    @Test
    void oidcDiscoveryBuildsTheClientRegistrationFromTheIssuer() throws IOException {
        // A real (loopback) discovery document: fromIssuerLocation is an HTTP call,
        // and stubbing it away would test nothing. The JDK's own HttpServer keeps
        // this dependency-free.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String issuer = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/.well-known/openid-configuration", exchange -> {
            byte[] body = """
                    {"issuer":"%s",
                     "authorization_endpoint":"%s/authorize",
                     "token_endpoint":"%s/token",
                     "jwks_uri":"%s/jwks",
                     "userinfo_endpoint":"%s/userinfo",
                     "response_types_supported":["code"],
                     "subject_types_supported":["public"],
                     "id_token_signing_alg_values_supported":["RS256"]}
                    """.formatted(issuer, issuer, issuer, issuer, issuer)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            SsoRegistration registration = row(SsoRegistration.Protocol.OIDC);
            registration.setOidcIssuerUri(issuer);
            registration.setOidcClientId("client-abc");
            // The stored form since V155: ciphertext. If the reader stopped decrypting,
            // the client below would carry "v1:<base64>" and every token exchange at the
            // customer's IdP would fail with invalid_client — a fault that only shows up
            // AFTER the user has authenticated, at the least debuggable moment.
            registration.setOidcClientSecret(CIPHER.encrypt("shhh"));
            stored(registration);

            ClientRegistration client =
                    new OidcClientRegistrations(repository, frontendUrls, CIPHER).findByRegistrationId(REGISTRATION);

            assertThat(client).isNotNull();
            assertThat(client.getClientId()).isEqualTo("client-abc");
            assertThat(client.getClientSecret()).isEqualTo("shhh");
            // The redirect_uri the customer registers at their IdP: frontend origin.
            assertThat(client.getRedirectUri())
                    .isEqualTo(FRONTEND + "/auth/sso/enterprise/oauth2/callback/" + REGISTRATION);
            assertThat(client.getScopes()).containsExactlyInAnyOrder("openid", "email", "profile");
            // The JWKS the id_token signature is verified against came from discovery,
            // which is the entire reason this half is not hand-rolled.
            assertThat(client.getProviderDetails().getJwkSetUri()).isEqualTo(issuer + "/jwks");

            // A row written BEFORE V155 holds plaintext and no version stamp. It must
            // still work — the startup conversion runs after the port is open, and a
            // registration that briefly refuses to handshake is an outage.
            registration.setOidcClientSecret("legacy-plaintext");
            stored(registration);
            assertThat(new OidcClientRegistrations(repository, frontendUrls, CIPHER)
                    .findByRegistrationId(REGISTRATION).getClientSecret())
                    .isEqualTo("legacy-plaintext");

            // A stamp this process has no key for (someone rotated BVISIONRY_ENCRYPTION_KEY)
            // resolves to NO registration — the handshake dies at the login page instead
            // of being attempted with an unusable secret.
            registration.setOidcClientSecret("v9:" + CIPHER.encrypt("shhh").substring(3));
            stored(registration);
            assertThat(new OidcClientRegistrations(repository, frontendUrls, CIPHER)
                    .findByRegistrationId(REGISTRATION)).isNull();
        } finally {
            server.stop(0);
        }
    }

    /**
     * The finding this section pins: discovery caches BOTH outcomes. While a
     * customer's IdP is down, every anonymous authorize request used to re-run
     * discovery on its own worker with 30-second timeouts — the exact amplifier
     * {@code SsoHandshakeRateLimitFilter}'s exemption of the handshake hops
     * assumes cannot exist.
     */
    @Test
    void aFailingIssuerIsProbedOnceNotOncePerRequest() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = discoveryServer(hits, new AtomicBoolean(false));
        try {
            stored(oidcRow(issuerOf(server)));
            OidcClientRegistrations registrations =
                    new OidcClientRegistrations(repository, frontendUrls, CIPHER);

            assertThat(registrations.findByRegistrationId(REGISTRATION)).isNull();
            // One findByRegistrationId may touch several well-known URIs; what must
            // not happen is any FURTHER outbound traffic while the failure is fresh.
            int probesForOneLookup = hits.get();
            assertThat(probesForOneLookup).isPositive();

            for (int i = 0; i < 4; i++) {
                assertThat(registrations.findByRegistrationId(REGISTRATION)).isNull();
            }
            assertThat(hits.get()).isEqualTo(probesForOneLookup);
        } finally {
            server.stop(0);
        }
    }

    /** …but a failure is believed only briefly: the issuer recovering is picked up. */
    @Test
    void aFailedDiscoveryIsRetriedOnceItsShortTtlLapses() throws IOException {
        AtomicBoolean healthy = new AtomicBoolean(false);
        HttpServer server = discoveryServer(new AtomicInteger(), healthy);
        try {
            stored(oidcRow(issuerOf(server)));
            OidcClientRegistrations registrations =
                    new OidcClientRegistrations(repository, frontendUrls, CIPHER);
            Instant base = Instant.now();
            registrations.setClock(Clock.fixed(base, ZoneOffset.UTC));

            assertThat(registrations.findByRegistrationId(REGISTRATION)).isNull();

            healthy.set(true);
            // Still inside the failure TTL: the cached failure answers, no re-probe.
            assertThat(registrations.findByRegistrationId(REGISTRATION)).isNull();

            registrations.setClock(Clock.fixed(base.plusSeconds(31), ZoneOffset.UTC));
            assertThat(registrations.findByRegistrationId(REGISTRATION)).isNotNull();
        } finally {
            server.stop(0);
        }
    }

    /**
     * Single-flight: while one worker is parked on the IdP round trip, a second
     * caller answers null immediately instead of queueing behind it — at most ONE
     * thread is ever pinned per identity provider.
     */
    @Test
    void aSecondCallerDoesNotQueueBehindAnInFlightProbe() throws Exception {
        CountDownLatch probeEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            probeEntered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        try {
            stored(oidcRow(issuerOf(server)));
            OidcClientRegistrations registrations =
                    new OidcClientRegistrations(repository, frontendUrls, CIPHER);

            Thread first = new Thread(() -> registrations.findByRegistrationId(REGISTRATION));
            first.start();
            assertThat(probeEntered.await(5, TimeUnit.SECONDS)).isTrue();

            // The probe is in flight and holding the lock: this caller must not park.
            long start = System.nanoTime();
            assertThat(registrations.findByRegistrationId(REGISTRATION)).isNull();
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .isLessThan(Duration.ofSeconds(2));

            release.countDown();
            first.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(first.isAlive()).isFalse();
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    private SsoRegistration oidcRow(String issuer) {
        SsoRegistration registration = row(SsoRegistration.Protocol.OIDC);
        registration.setOidcIssuerUri(issuer);
        registration.setOidcClientId("client-abc");
        registration.setOidcClientSecret(CIPHER.encrypt("shhh"));
        return registration;
    }

    private static String issuerOf(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A loopback issuer that counts every request and answers 500 until healthy. */
    private static HttpServer discoveryServer(AtomicInteger hits, AtomicBoolean healthy) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String issuer = issuerOf(server);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            if (!healthy.get()) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            byte[] body = """
                    {"issuer":"%s",
                     "authorization_endpoint":"%s/authorize",
                     "token_endpoint":"%s/token",
                     "jwks_uri":"%s/jwks",
                     "userinfo_endpoint":"%s/userinfo",
                     "response_types_supported":["code"],
                     "subject_types_supported":["public"],
                     "id_token_signing_alg_values_supported":["RS256"]}
                    """.formatted(issuer, issuer, issuer, issuer, issuer)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    @Test
    void anUnreachableIssuerResolvesToNoRegistrationRatherThanThrowing() {
        SsoRegistration registration = row(SsoRegistration.Protocol.OIDC);
        // Port 1 on loopback: nothing listens, so discovery fails fast.
        registration.setOidcIssuerUri("http://127.0.0.1:1");
        registration.setOidcClientId("client-abc");
        registration.setOidcClientSecret("shhh");
        stored(registration);

        assertThat(new OidcClientRegistrations(repository, frontendUrls, CIPHER)
                .findByRegistrationId(REGISTRATION)).isNull();
    }
}
