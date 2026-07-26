package com.bvisionry.pipeline.controller;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The route floor on pillar + question authoring, with the FILTER CHAIN ON.
 *
 * <p>Everything under {@code /api/pipelines} used to fall through to
 * {@code anyRequest().authenticated()}, which made the controllers'
 * {@code @PreAuthorize} the ONLY thing standing between a signed-in MEMBER and
 * the instrument's own definition — one deleted annotation from an open door,
 * with no layer beneath to catch it. {@code SecurityConfig} now floors the whole
 * subtree at SUPER_ADMIN; these tests are what fail if that rule is dropped,
 * mis-ordered or has its pattern narrowed.
 *
 * <p>The discriminator is {@link MvcResult#getHandler()}, not the status code: a
 * route-rule refusal happens in the filter chain, so the DispatcherServlet never
 * resolves a handler. A 403 alone proves nothing here — the method annotation
 * produces an identical one. The SUPER_ADMIN control is the other half of the
 * claim: same URL, handler RESOLVED (and a 404 from the service, since the
 * pipeline id is random), which is what shows the matcher gates by role instead
 * of locking the surface out for everyone.
 *
 * <p>Reads only, and no CSRF token is needed: a GET is enough to pin an
 * authorization rule that is method-independent, and it keeps a CsrfFilter
 * refusal — which has nothing to do with authorization — out of the result.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfDockerAvailable
class PillarRouteSecurityIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final UUID PIPELINE = UUID.randomUUID();

    /** The bare collection path — the one a trailing {@code /**} is easiest to get wrong on. */
    private static final String PILLARS = "/api/pipelines/%s/pillars".formatted(PIPELINE);

    /** A descendant, and a second controller (QuestionController) under the same rule. */
    private static final String QUESTIONS = "/api/pipelines/%s/pillars/%s/questions"
            .formatted(PIPELINE, UUID.randomUUID());

    /**
     * A member-readable SIBLING, deliberately included: it shares the
     * {@code /api/pipelines/*} prefix but has no {@code /pillars} segment, so the
     * new matcher must not reach it. Without this the audit's central claim —
     * "no legitimate surface gets 403ed" — would be untested.
     */
    private static final String BANDS = "/api/pipelines/%s/bands".formatted(PIPELINE);

    @Autowired private MockMvc mockMvc;

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

    private MvcResult getAnonymously(String path) throws Exception {
        return mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andReturn();
    }

    private MvcResult getAs(UserRole role, String path) throws Exception {
        return mockMvc.perform(get(path).with(authentication(principal(role))))
                .andExpect(status().isForbidden())
                .andReturn();
    }

    @Test
    void anAnonymousCallerIsRefusedTheCollectionBeforeAnyControllerIsReached() throws Exception {
        assertThat(getAnonymously(PILLARS).getHandler())
                .as("no handler resolved — the entry point refused it in the filter chain")
                .isNull();
    }

    @Test
    void anAnonymousCallerIsRefusedTheQuestionsSubtree() throws Exception {
        assertThat(getAnonymously(QUESTIONS).getHandler()).isNull();
    }

    @Test
    void theHttpLayerRefusesAMemberTheBarePillarCollection() throws Exception {
        // The `/**` tail has to match zero trailing segments for this to be a 403
        // in the filter chain rather than a 403 from @PreAuthorize.
        assertThat(getAs(UserRole.MEMBER, PILLARS).getHandler())
                .as("no handler resolved — the route rule refused it, not @PreAuthorize")
                .isNull();
    }

    @Test
    void theHttpLayerRefusesAMemberTheQuestionsSubtree() throws Exception {
        assertThat(getAs(UserRole.MEMBER, QUESTIONS).getHandler()).isNull();
    }

    @Test
    void theHttpLayerRefusesAnOrgAdminToo() throws Exception {
        // Pillars are PLATFORM content, not org content — the highest non-platform
        // role has no more business authoring them than a member does.
        assertThat(getAs(UserRole.ORG_ADMIN, PILLARS).getHandler()).isNull();
    }

    @Test
    void aSuperAdminClearsTheHttpLayerAndReachesTheController() throws Exception {
        MvcResult result = mockMvc.perform(get(PILLARS)
                        .with(authentication(principal(UserRole.SUPER_ADMIN))))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(result.getHandler())
                .as("handler resolved — this 404 is the service answering, not the route rule")
                .isNotNull();
    }

    @Test
    void theMatcherDoesNotReachTheMemberReadableBandsSibling() throws Exception {
        // /api/pipelines/{id}/bands is @PreAuthorize("isAuthenticated()") by design
        // (the party being measured may read the yardstick). It contains no
        // `/pillars` segment, so the new SUPER_ADMIN floor must leave it alone: a
        // MEMBER still reaches the controller, and the 404 comes from the service.
        MvcResult result = mockMvc.perform(get(BANDS)
                        .with(authentication(principal(UserRole.MEMBER))))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(result.getHandler())
                .as("handler resolved — a route floor that swallowed this sibling "
                        + "would be worse than the gap it closes")
                .isNotNull();
    }
}
