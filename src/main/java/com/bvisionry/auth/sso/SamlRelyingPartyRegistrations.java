package com.bvisionry.auth.sso;

import com.bvisionry.config.FrontendUrls;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * Database-backed {@link RelyingPartyRegistrationRepository} — the multi-tenant
 * SAML seam Spring Security documents for exactly this case.
 *
 * <p>The IdP half comes from {@link RelyingPartyRegistrations#collectionFromMetadata},
 * the reference's "the metadata came from a database" path: it reads the
 * EntityDescriptor stored on the row, including the signing certificate that
 * {@code OpenSaml5AuthenticationProvider} will validate the assertion against. No
 * trust material is configured anywhere else, so rotating a customer's certificate
 * is an UPDATE on one row.
 *
 * <p>The SP half is built from the FRONTEND origin, not the backend's. Every
 * browser- and IdP-visible SSO URL in this platform is a frontend URL that relays
 * to the backend server-side (see {@code web/src/lib/oauth-relay.ts}): the API's
 * auth cookies are host-only, so a session minted on the API origin would not
 * exist on the origin the app is served from. The handshake is therefore
 * relayed exactly like the Google flow already is.
 */
@Component
@RequiredArgsConstructor
public class SamlRelyingPartyRegistrations implements RelyingPartyRegistrationRepository {

    private static final Logger log = LoggerFactory.getLogger(SamlRelyingPartyRegistrations.class);

    private final SsoRegistrationRepository repository;
    private final FrontendUrls frontendUrls;

    /** Browser-visible ACS path; the frontend relays a POST here to the backend's loginProcessingUrl. */
    public static String assertionConsumerServiceLocation(FrontendUrls urls, String registrationId) {
        return urls.path("/auth/sso/enterprise/saml2/acs/" + registrationId);
    }

    @Override
    public RelyingPartyRegistration findByRegistrationId(String registrationId) {
        SsoRegistration row = repository.findByRegistrationIdAndEnabledTrue(registrationId)
                .filter(r -> r.getProtocol() == SsoRegistration.Protocol.SAML)
                .orElse(null);
        if (row == null) {
            return null;
        }
        try {
            // ponytail: re-parsed on each of the two hops per login, deliberately
            // uncached — unlike the OIDC side this is a local CPU parse of a blob we
            // already hold, not an outbound request, so it is not the anonymous
            // amplifier that made a cache mandatory there. Add one keyed on the row's
            // updatedAt if it ever shows up in a latency profile.
            Collection<RelyingPartyRegistration.Builder> builders = RelyingPartyRegistrations
                    .collectionFromMetadata(new ByteArrayInputStream(
                            row.getSamlMetadata().getBytes(StandardCharsets.UTF_8)));
            if (builders.isEmpty()) {
                log.warn("SAML metadata for registration {} declares no asserting party", registrationId);
                return null;
            }
            return builders.iterator().next()
                    .registrationId(registrationId)
                    .entityId(frontendUrls.path("/saml2/sp/" + registrationId))
                    .assertionConsumerServiceLocation(
                            assertionConsumerServiceLocation(frontendUrls, registrationId))
                    .build();
        } catch (RuntimeException invalidMetadata) {
            // Unparseable metadata is a configuration error, not a request error:
            // answer "no such registration" so the handshake fails closed at the
            // login page instead of surfacing a stack trace to the browser.
            log.error("SAML metadata for registration {} could not be parsed: {}",
                    registrationId, invalidMetadata.getMessage());
            return null;
        }
    }
}
