package com.bvisionry.auth.sso;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Both route floors the SSO feature depends on, with the FILTER CHAIN ON.
 *
 * <p>Two claims, and they pull in opposite directions:
 * <ol>
 *   <li>{@code /api/admin/sso-registrations/**} is SUPER_ADMIN at the ROUTE layer,
 *       not only at the controller's {@code @PreAuthorize}. Writing a registration
 *       decides which email domain a customer's identity provider may speak for,
 *       so a lost annotation must degrade to "platform admins only", never to "any
 *       signed-in user".</li>
 *   <li>{@code /api/auth/sso/handshake/**} is reachable ANONYMOUSLY, because a
 *       user in the middle of proving who they are has no session yet. That is the
 *       second filter chain doing its job; on the main chain these paths would hit
 *       {@code anyRequest().authenticated()} and 401, and enterprise SSO would be
 *       unreachable for exactly the people it is for.</li>
 * </ol>
 *
 * <p>The discriminator is {@link MvcResult#getHandler()}, not the status code: a
 * route-rule refusal happens in the filter chain, so no handler is ever resolved.
 * A 403 on its own proves nothing — {@code @PreAuthorize} produces an identical
 * one from inside the DispatcherServlet.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfDockerAvailable
class SsoRouteSecurityIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String REGISTRATIONS = "/api/admin/sso-registrations";
    private static final String ONE_REGISTRATION = REGISTRATIONS + "/" + UUID.randomUUID();
    private static final String HANDSHAKE_START = "/api/auth/sso/handshake/start?email=nobody@nowhere.invalid";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    /** An in-memory principal — the route layer reads authorities, never the database. */
    private static Authentication principal(UserRole role) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(role.name().toLowerCase() + "@route.invalid");
        user.setName(role.name());
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        return new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority(role.name())));
    }

    @Test
    void anAnonymousCallerNeverReachesTheRegistrationAdminSurface() throws Exception {
        MvcResult result = mockMvc.perform(get(REGISTRATIONS))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(result.getHandler())
                .as("no handler resolved — the entry point refused it in the filter chain")
                .isNull();
    }

    @Test
    void theHttpLayerRefusesAMemberTheBareRegistrationCollection() throws Exception {
        // The `/**` tail has to match zero trailing segments for this to be a filter
        // chain 403 rather than a @PreAuthorize one.
        MvcResult result = mockMvc.perform(get(REGISTRATIONS).with(authentication(principal(UserRole.MEMBER))))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(result.getHandler())
                .as("no handler resolved — the route rule refused it, not @PreAuthorize")
                .isNull();
    }

    @Test
    void theHttpLayerRefusesAnOrgAdminToo() throws Exception {
        // An org admin managing their own registration would mean an org admin
        // asserting which email domain they control. That is the takeover.
        MvcResult result = mockMvc.perform(get(ONE_REGISTRATION)
                        .with(authentication(principal(UserRole.ORG_ADMIN))))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(result.getHandler()).isNull();
    }

    @Test
    void theHttpLayerRefusesACoachTheSubtree() throws Exception {
        MvcResult result = mockMvc.perform(get(ONE_REGISTRATION)
                        .with(authentication(principal(UserRole.COACH))))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(result.getHandler()).isNull();
    }

    @Test
    void aSuperAdminClearsTheHttpLayerAndReachesTheController() throws Exception {
        // The other half of the claim: the matcher gates by role rather than locking
        // the surface out for everyone. An empty list is the service answering.
        MvcResult result = mockMvc.perform(get(REGISTRATIONS)
                        .with(authentication(principal(UserRole.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getHandler())
                .as("handler resolved — this 200 is the controller, not the route rule")
                .isNotNull();
    }

    @Test
    void theHandshakeEntryPointIsAnonymousAndRedirects() throws Exception {
        // Proves the second filter chain matched: on the main chain this path falls
        // to anyRequest().authenticated() and would be a 401 with no handler.
        MvcResult result = mockMvc.perform(get(HANDSHAKE_START))
                .andExpect(status().isFound())
                .andReturn();
        assertThat(result.getHandler())
                .as("handler resolved anonymously — the SSO chain permits the handshake")
                .isNotNull();
        assertThat(result.getResponse().getHeader("Location"))
                .as("an unknown domain lands back on the login page, never a 404 or a 500")
                .contains("/login?error=sso_no_registration");
    }

    /**
     * The SSO chain's BLAST RADIUS. {@code securityMatcher} is the only thing
     * keeping a permitAll, CSRF-disabled, session-creating chain with no JWT filter
     * off the rest of the API — and widening it from
     * {@code /api/auth/sso/handshake/**} to, say, {@code /api/auth/**} produces no
     * failure anywhere else in the suite while silently opening every auth endpoint.
     *
     * <p>{@code /api/auth/me} is the probe: it is a sibling under {@code /api/auth}
     * that the handshake chain must NOT match, so it has to keep coming back 401
     * from the main chain's entry point with no handler resolved.
     */
    @Test
    void theHandshakeChainDoesNotReachTheRestOfTheAuthSurface() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(result.getHandler())
                .as("no handler resolved — /api/auth/me is still the main chain's, not the SSO chain's")
                .isNull();
    }

    @Test
    void anUnknownSamlRegistrationFailsClosedToTheLoginPage() throws Exception {
        // No filter matches an unknown registration, so the request used to fall
        // through to a bare 404. SsoHandshakeFailClosedFilter turns every unroutable
        // handshake into the login page — the case that matters is an administrator
        // disabling a registration while somebody is mid-sign-in.
        MvcResult result = mockMvc.perform(get("/api/auth/sso/handshake/saml2/authenticate/nope"))
                .andExpect(status().isFound())
                .andReturn();
        assertThat(result.getResponse().getHeader("Location")).contains("/login?error=sso_error");
    }

    /**
     * The single decision this chain exists to make: {@code IF_REQUIRED}, so the
     * handshake gets somewhere to keep the {@code AuthnRequest} that
     * {@code InResponseTo} is later validated against.
     *
     * <p>Driving a REAL registration is what makes this test able to fail. With no
     * registration the filter never runs, no session is ever created, and the
     * assertion would pass for the wrong reason.
     */
    @Test
    void theHandshakeCreatesTheSessionItsReplayProtectionDependsOn() throws Exception {
        jdbc.update("DELETE FROM sso_registrations WHERE registration_id = 'orgb-saml'");
        UUID orgId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, is_active) VALUES (?, 'OrgB', TRUE)", orgId);
        jdbc.update("""
                INSERT INTO sso_registrations
                    (id, registration_id, org_id, protocol, email_domain, display_name, enabled, saml_metadata)
                VALUES (?, 'orgb-saml', ?, 'SAML', 'orgb-saml.test', 'OrgB IdP', TRUE, ?)
                """, UUID.randomUUID(), orgId, SsoTestFixtures.IDP_METADATA);

        MvcResult result = mockMvc.perform(get("/api/auth/sso/handshake/saml2/authenticate/orgb-saml"))
                .andExpect(status().isFound())
                .andReturn();

        assertThat(result.getResponse().getHeader("Location"))
                .as("an AuthnRequest was built and the browser is being sent to the IdP")
                .startsWith("https://idp.orgb.test/sso")
                .contains("SAMLRequest=");
        HttpSession session = result.getRequest().getSession(false);
        assertThat(session)
                .as("no session means nothing to validate InResponseTo against at the ACS")
                .isNotNull();
        assertThat(Collections.list(session.getAttributeNames()))
                .as("the saved AuthnRequest IS the replay protection")
                .isNotEmpty();
    }

    @Test
    void anUnknownOidcRegistrationFailsClosedInsteadOfThrowingA500() throws Exception {
        // DefaultOAuth2AuthorizationRequestResolver throws IllegalArgumentException
        // when the registration repository answers null, which reached the browser as
        // an anonymous 500 before the fail-closed filter existed.
        MvcResult result = mockMvc.perform(get("/api/auth/sso/handshake/oauth2/authorization/nope"))
                .andExpect(status().isFound())
                .andReturn();
        assertThat(result.getResponse().getHeader("Location")).contains("/login?error=sso_error");
    }
}
