package com.bvisionry.catalog.web;

import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Where the PUBLIC course catalog stops, with the filter chain on.
 *
 * <p>{@code SecurityConfig} opens exactly two catalog paths to anonymous
 * callers:
 *
 * <pre>
 *   .requestMatchers(GET, "/api/v1/courses", "/api/v1/courses/{slug}").permitAll()
 * </pre>
 *
 * <p>{@code {slug}} matches ONE path segment. That single fact is the only thing
 * keeping {@code GET /api/v1/courses/{slug}/content/{contentId}} — which returns
 * the lesson body and freshly presigned media URLs — behind authentication, and
 * it is a fact a reasonable-looking edit destroys: widening the matcher to
 * {@code /api/v1/courses/**} (to "let the marketing site fetch a preview", say)
 * publishes every lesson body in the catalog, including DRAFT and paid ones,
 * because {@code EnrollmentService.lessonContent} then runs with no principal.
 * There is no compile error and no failing controller test on that path — so
 * this file is the thing that goes red.
 *
 * <p>The discriminator is {@link MvcResult#getHandler()}, not the status alone.
 * A route-rule refusal happens in the filter chain, so no handler is ever
 * resolved; a controller-level refusal produces a similar status with a handler
 * present. Only the handler tells the two apart, and only the route layer is
 * under test here — {@code PublicCourseDetailShapeTest} pins the complementary
 * half (that the public DTO carries no body to leak in the first place).
 *
 * <p>Random ids throughout: a route rule is data-independent, and depending on
 * seeded rows would make this fail for reasons that have nothing to do with
 * authorization.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfDockerAvailable
class CatalogRouteSecurityIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SLUG = "no-such-course-" + UUID.randomUUID();
    private static final String DETAIL = "/api/v1/courses/" + SLUG;
    private static final String CONTENT = DETAIL + "/content/" + UUID.randomUUID();

    @Autowired private MockMvc mockMvc;

    @Test
    void theCourseListIsGenuinelyPublic() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/courses"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getHandler())
                .as("the marketing site lists courses with no session")
                .isNotNull();
    }

    @Test
    void courseDetailIsGenuinelyPublic() throws Exception {
        // 404 because the slug is random — but a 404 from the SERVICE, which is
        // what proves the filter chain let the request through. Without this the
        // test below could pass on a catalog that was accidentally closed
        // entirely, and "everything is 401" is not the property being claimed.
        MvcResult result = mockMvc.perform(get(DETAIL))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(result.getHandler())
                .as("handler resolved — this 404 is the service answering, not a route refusal")
                .isNotNull();
    }

    @Test
    void theLessonBodyRouteRefusesAnAnonymousCallerBeforeAnyControllerRuns() throws Exception {
        MvcResult result = mockMvc.perform(get(CONTENT))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(result.getHandler())
                .as("no handler resolved — {slug} is one segment, so the public matcher "
                        + "never reaches /content/**; widening it to /api/v1/courses/** "
                        + "publishes every lesson body in the catalog")
                .isNull();
    }
}
