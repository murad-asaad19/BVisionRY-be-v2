package com.bvisionry.communication;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Layer 1 of the three-layer defense, with the FILTER CHAIN ON — the rest of
 * the announcement suite runs {@code addFilters = false}, which skips
 * {@code SecurityConfig}'s route rules entirely and so can never catch one
 * being dropped or mis-ordered.
 *
 * <p>The discriminator is {@link MvcResult#getHandler()}: a route-rule refusal
 * happens in the filter chain, so the DispatcherServlet never resolves a
 * handler at all. The ORG_ADMIN control is the other half of the claim — same
 * URL, same CSRF token, refused only at the METHOD layer (by
 * {@code @orgAccess}, since that principal belongs to no org), and its handler
 * IS resolved. Without it a "403" assertion would also pass if the route
 * matcher had accidentally locked everyone out.
 *
 * <p>CSRF is supplied explicitly: the backend runs cookie-repository CSRF, so
 * a POST without a token is refused by the CsrfFilter for a reason that has
 * nothing to do with authorization.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfDockerAvailable
class AnnouncementRouteSecurityIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final UUID ORG = UUID.randomUUID();

    private static final String PATH = "/api/organizations/%s/cohorts/%s/announcements"
            .formatted(ORG, UUID.randomUUID());

    /** The picker. Its query has no role predicate, so layers 1+2 are all it has. */
    private static final String COHORTS_PATH = "/api/organizations/%s/announcement-cohorts"
            .formatted(ORG);

    @Autowired private MockMvc mockMvc;

    /** An in-memory principal — layer 1 reads authorities, never the database. */
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

    private MvcResult postAs(UserRole role) throws Exception {
        return mockMvc.perform(post(PATH)
                        .with(authentication(principal(role)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Hi all.\"}"))
                .andExpect(status().isForbidden())
                .andReturn();
    }

    @Test
    void theHttpLayerRefusesAMemberBeforeAnyControllerIsReached() throws Exception {
        assertThat(postAs(UserRole.MEMBER).getHandler())
                .as("no handler resolved — the route rule refused it in the filter chain")
                .isNull();
    }

    @Test
    void theHttpLayerRefusesAnInstructorBeforeAnyControllerIsReached() throws Exception {
        assertThat(postAs(UserRole.INSTRUCTOR).getHandler()).isNull();
    }

    @Test
    void aBroadcastRoleClearsTheHttpLayerAndIsJudgedByTheMethodLayer() throws Exception {
        assertThat(postAs(UserRole.ORG_ADMIN).getHandler())
                .as("handler resolved — this 403 came from @orgAccess, not the route rule")
                .isNotNull();
    }

    @Test
    void theHttpLayerRefusesAMemberTheCohortPicker() throws Exception {
        // The picker is the endpoint whose data layer does the LEAST independent
        // work: cohortsInOrg() lists every cohort in the org with no role
        // predicate at all, so its denial rests entirely on layers 1 and 2. If
        // the route rule ever stopped covering it, only this test would notice.
        MvcResult result = mockMvc.perform(get(COHORTS_PATH)
                        .with(authentication(principal(UserRole.MEMBER))))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(result.getHandler())
                .as("no handler resolved — the route rule refused it in the filter chain")
                .isNull();
    }
}
