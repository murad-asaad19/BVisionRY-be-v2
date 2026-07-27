package com.bvisionry.organization;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/organizations/{id}/branding} end to end: the authorization split
 * (a wider READ than WRITE, deliberately — the branded surface is every
 * signed-in page, so every role in the org has to be able to read it), the IDOR
 * guard over HTTP, and the storage-layer CHECK that backs it up.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@EnabledIfDockerAvailable
class OrganizationBrandingIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;

    private Organization own;
    private Organization other;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        organizationRepository.deleteAll();
        own = newOrg("Own");
        other = newOrg("Other");
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    private Organization newOrg(String name) {
        Organization o = new Organization();
        o.setName(name);
        o.setActive(true);
        return organizationRepository.save(o);
    }

    private static String markerFor(Organization o) {
        return "minio://bvisionry-media/org/" + o.getId() + "/branding/"
                + UUID.randomUUID() + "-logo.png";
    }

    private static String body(String colour, String marker) {
        return """
                {"brandColor": %s, "logoMarker": %s}
                """.formatted(quote(colour), quote(marker));
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    // ------------------------------------------------- default (no branding)

    @Test
    void anOrgWithNoBrandingReadsAsAllNulls() throws Exception {
        TestAuthentication.authenticateAsOrgAdmin(userRepository, own);

        mockMvc.perform(get("/api/organizations/" + own.getId() + "/branding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandColor", is(nullValue())))
                .andExpect(jsonPath("$.logoMarker", is(nullValue())))
                .andExpect(jsonPath("$.logoUrl", is(nullValue())));
    }

    @Test
    void clearingBrandingReturnsTheOrgToTheDefaultTheme() throws Exception {
        TestAuthentication.authenticateAsOrgAdmin(userRepository, own);
        String marker = markerFor(own);

        mockMvc.perform(put("/api/organizations/" + own.getId() + "/branding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("#0A5CFF", marker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandColor", is("#0a5cff")))
                .andExpect(jsonPath("$.logoMarker", is(marker)));

        mockMvc.perform(put("/api/organizations/" + own.getId() + "/branding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandColor", is(nullValue())))
                .andExpect(jsonPath("$.logoMarker", is(nullValue())))
                .andExpect(jsonPath("$.logoUrl", is(nullValue())));

        assertThat(organizationRepository.findById(own.getId()).orElseThrow().getBrandColor())
                .isNull();
    }

    // -------------------------------------------------------- authorization

    /**
     * ASSERTS THE BODY, NOT JUST THE STATUS, and that is the whole point.
     *
     * <p>Two independent layers refuse a cross-org call and both answer 403:
     * {@code OrgAccessInterceptor}, which runs for every
     * {@code /api/organizations/{id}/**} path, and the {@code @PreAuthorize}
     * expression on the handler. A status-only assertion is therefore satisfied
     * by the interceptor alone - widening the annotation to
     * {@code isAuthenticated()} or even {@code permitAll()} leaves it green, so
     * the predicate this ticket added would be dead weight as far as the suite
     * knows.
     *
     * <p>The two layers are distinguishable by their bodies: the interceptor
     * writes a {@code message} field, Spring Security writes an RFC-7807
     * ProblemDetail with {@code "title":"Forbidden"}. Asserting the shape pins
     * WHICH layer answered; the companion test below pins that the annotation
     * answers when the interceptor cannot.
     */
    @Test
    void orgAdminOfAnotherOrgIsRefusedBothVerbs() throws Exception {
        TestAuthentication.authenticateAsOrgAdmin(userRepository, own);

        mockMvc.perform(get("/api/organizations/" + other.getId() + "/branding"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("do not belong to this organization")));
        mockMvc.perform(put("/api/organizations/" + other.getId() + "/branding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("#0a5cff", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("do not belong to this organization")));
    }

    /**
     * The falsifier for the {@code @PreAuthorize} expression itself.
     *
     * <p>{@code OrgAccessInterceptor} grants a SUPER_ADMIN everything and lets an
     * in-org caller through, so for a MEMBER of THIS org it does not fire at
     * all - the only thing between that member and a branding WRITE is the
     * annotation's {@code hasAuthority('ORG_ADMIN')}. Spring Security's own
     * handler answers, and its ProblemDetail body is how we know it was the
     * annotation and not the interceptor. Widening the annotation kills this
     * test; widening it kills nothing in the cross-org test above.
     */
    @Test
    void theAnnotationItselfRefusesAnInOrgMemberWriting() throws Exception {
        TestAuthentication.authenticateAsMember(userRepository, own);

        mockMvc.perform(put("/api/organizations/" + own.getId() + "/branding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("#0a5cff", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title", is("Forbidden")));
    }

    /**
     * The read/write asymmetry, pinned. A MEMBER must be able to READ their own
     * org's branding — the app shell renders it on every page for every role —
     * and must never be able to WRITE it.
     */
    @Test
    void memberOfTheOrgMayReadButNeverWrite() throws Exception {
        TestAuthentication.authenticateAsMember(userRepository, own);

        mockMvc.perform(get("/api/organizations/" + own.getId() + "/branding"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/organizations/" + own.getId() + "/branding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("#0a5cff", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title", is("Forbidden")));
    }

    /**
     * THE FALSIFIER FOR THE READ PREDICATE, and it needs a path the interceptor
     * cannot see.
     *
     * <p>{@code OrgAccessInterceptor} matches the canonical 8-4-4-4-12 UUID shape.
     * {@code UUID.fromString} is LENIENT and accepts short group
     * forms, so {@code 0-0-0-0-1} is a valid path variable that resolves to
     * {@code 00000000-0000-0000-0000-000000000001} while never matching that
     * pattern: the interceptor returns "not an org-scoped request" and the
     * {@code @PreAuthorize} expression is the ONLY gate left.
     *
     * <p>So this pins the predicate itself. With it, an out-of-org caller is
     * refused (403) before the service is ever entered; widen the annotation to
     * {@code isAuthenticated()} or {@code permitAll()} and the handler runs,
     * reaching a lookup that answers 404 — a different status, which is how the
     * mutant dies. Were such an org to exist, the widened annotation would
     * serve its branding to a foreigner.
     *
     * <p>That the interceptor abstains here is a PRE-EXISTING platform-wide gap and is
     * still open after the pattern was tightened to the canonical shape: the tightening
     * closed the OPPOSITE direction (over-match), not this one. Branding is closed
     * regardless, because it carries the predicate — which is the point.
     */
    @Test
    void theReadPredicateRefusesOutOfOrgOnAPathTheInterceptorDoesNotMatch() throws Exception {
        TestAuthentication.authenticateAsMember(userRepository, own);

        mockMvc.perform(get("/api/organizations/0-0-0-0-1/branding"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title", is("Forbidden")));
    }

    /** The same path, same caller, WRITING — the write predicate's falsifier. */
    @Test
    void theWritePredicateRefusesOutOfOrgOnAPathTheInterceptorDoesNotMatch() throws Exception {
        TestAuthentication.authenticateAsOrgAdmin(userRepository, own);

        mockMvc.perform(put("/api/organizations/0-0-0-0-1/branding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("#0a5cff", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title", is("Forbidden")));
    }

    /**
     * THE OTHER DIRECTION, over HTTP, and the status is the whole assertion.
     *
     * <p>36 dashes is 36 characters of the old {@code [A-Fa-f0-9-]} class, so the old
     * {@code OrgAccessInterceptor} pattern MATCHED and {@code UUID.fromString} threw out
     * of {@code preHandle}. {@code GlobalExceptionHandler} has no
     * {@code IllegalArgumentException} handler, so a client sending a malformed org id
     * got a 500 — an unexpected-error body, and an error event recorded — for what is a
     * bad request. The canonical-shape pattern abstains instead, and the
     * {@code @PathVariable UUID} binder answers 400 via {@code handleTypeMismatch}.
     *
     * <p>Only an end-to-end test can see this: the interceptor unit test can prove
     * preHandle no longer throws, but 500-vs-400 is decided in the dispatcher.
     */
    @Test
    void aMalformedOrgIdIsABadRequestNotAServerError() throws Exception {
        TestAuthentication.authenticateAsOrgAdmin(userRepository, own);

        mockMvc.perform(get("/api/organizations/" + "-".repeat(36) + "/branding"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void memberOfAnotherOrgCannotEvenRead() throws Exception {
        TestAuthentication.authenticateAsMember(userRepository, own);

        mockMvc.perform(get("/api/organizations/" + other.getId() + "/branding"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------ IDOR guard

    @Test
    void anOrgAdminCannotPersistAnotherOrgsMarker() throws Exception {
        TestAuthentication.authenticateAsOrgAdmin(userRepository, own);

        mockMvc.perform(put("/api/organizations/" + own.getId() + "/branding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, markerFor(other))))
                .andExpect(status().isBadRequest());

        assertThat(organizationRepository.findById(own.getId()).orElseThrow().getBrandLogoMarker())
                .isNull();
    }

    @Test
    void anOrgAdminCannotPersistAMarkerForAnotherTenantsLessonMedia() throws Exception {
        TestAuthentication.authenticateAsOrgAdmin(userRepository, own);

        mockMvc.perform(put("/api/organizations/" + own.getId() + "/branding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "minio://bvisionry-media/pdf/2f1c-financials.pdf")))
                .andExpect(status().isBadRequest());
    }

    /**
     * A marker that CONTAINS this org's branding prefix but resolves to the
     * victim's object. A {@code LIKE} formulation accepts it (SQL's % matches
     * a slash); the anchored regex in V154 and OWN_ORG_MARKER do not. It must
     * be a 400 from the service, never a 500 from the column.
     */
    @Test
    void aNestedKeyThatMerelyContainsTheOrgPrefixIsRefusedAsABadRequest() throws Exception {
        TestAuthentication.authenticateAsOrgAdmin(userRepository, own);

        mockMvc.perform(put("/api/organizations/" + own.getId() + "/branding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "minio://bvisionry-media/org/" + own.getId()
                                + "/branding/x/org/" + other.getId() + "/branding/y.png")))
                .andExpect(status().isBadRequest());
    }

    /**
     * An upper-case UUID in the marker. V154 compares against a lower-case
     * {@code id::text} with a case-sensitive operator, so this must be a clean
     * 400 from the service rather than a 500 when the constraint rejects it.
     */
    @Test
    void anUpperCaseOrgIdInTheMarkerIsABadRequestNotAServerError() throws Exception {
        TestAuthentication.authenticateAsOrgAdmin(userRepository, own);

        mockMvc.perform(put("/api/organizations/" + own.getId() + "/branding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "minio://bvisionry-media/org/"
                                + own.getId().toString().toUpperCase(java.util.Locale.ROOT)
                                + "/branding/x-logo.png")))
                .andExpect(status().isBadRequest());
    }

    /**
     * The layer that survives a future write path forgetting the service guard:
     * the column itself refuses a marker outside the row's own org prefix.
     */
    @Test
    void theDatabaseRefusesAForeignMarkerEvenBypassingTheService() {
        Organization target = organizationRepository.findById(own.getId()).orElseThrow();
        target.setBrandLogoMarker(markerFor(other));

        assertThatThrownBy(() -> organizationRepository.saveAndFlush(target))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** The same LIKE-vs-regex hole, at the storage layer. */
    @Test
    void theDatabaseRefusesANestedKeyThatMerelyContainsTheOrgPrefix() {
        Organization target = organizationRepository.findById(own.getId()).orElseThrow();
        target.setBrandLogoMarker("minio://bvisionry-media/org/" + own.getId()
                + "/branding/x/org/" + other.getId() + "/branding/y.png");

        assertThatThrownBy(() -> organizationRepository.saveAndFlush(target))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseRefusesANonHexBrandColour() {
        Organization target = organizationRepository.findById(own.getId()).orElseThrow();
        target.setBrandColor("red");

        assertThatThrownBy(() -> organizationRepository.saveAndFlush(target))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------- validation

    @Test
    void aNonHexBrandColourIsRejectedAtTheEdge() throws Exception {
        TestAuthentication.authenticateAsOrgAdmin(userRepository, own);

        mockMvc.perform(put("/api/organizations/" + own.getId() + "/branding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("#000;}:root{--primary:red", null)))
                .andExpect(status().isBadRequest());
    }
}
