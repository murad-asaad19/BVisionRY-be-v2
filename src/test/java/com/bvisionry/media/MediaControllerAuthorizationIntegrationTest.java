package com.bvisionry.media;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.organization.OrganizationRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may reach {@code POST /api/v1/media} and {@code /media/presign} now that
 * ORG_ADMIN is admitted for white-label logo uploads.
 *
 * <p>Two layers compose into the claim "an ORG_ADMIN can upload images into
 * their own org and nothing else", and neither is provable without the other:
 * the {@code @PreAuthorize} expression admits an ORG_ADMIN <em>only</em> with an
 * {@code orgId} they pass {@code @orgAccess.isInOrg} for (403 otherwise), and
 * {@code MediaUploadPolicy} then refuses any org-scoped upload that is not
 * {@code kind=image} (400). A test of the second layer alone would still pass
 * if the first admitted everyone.
 *
 * <p>NO SUCCESS PATH IS ASSERTED HERE, deliberately. A 201 needs a reachable
 * object store, and the default {@code bvisionry.minio.*} endpoints point at
 * {@code localhost:9000} — which on a developer machine is the DEV stack. A
 * "successful upload" assertion would write a real object into shared storage.
 * The allowed shape is instead proved by the CONTENT-TYPE refusal: reaching the
 * allowlist at all means authorization admitted the caller, and that check runs
 * before anything touches MinIO.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@EnabledIfDockerAvailable
class MediaControllerAuthorizationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;

    /**
     * A SPY, not a mock, and the distinction is load-bearing: every refusal in
     * this class is produced by the REAL {@code MediaUploadPolicy} inside this
     * service, so replacing the bean outright would turn each of those 400s into
     * a 201 and quietly delete the tests. A spy keeps the real behaviour and
     * still records the arguments, which is what the two wiring tests below need
     * — the 201 BODY requires a live object store, but
     * {@code upload(file, kind, orgId)} does not, and the org id is the entire
     * tenant boundary.
     */
    @MockitoSpyBean private MediaService mediaService;

    private Organization own;
    private Organization other;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        organizationRepository.deleteAll();
        own = newOrg("Own");
        other = newOrg("Other");
    }

    /**
     * Stub ONLY the object-store hop, and only where a success path is needed.
     * Default MinIO config points at localhost:9000 — a developer machine's DEV
     * stack — so a test that really uploaded would write into shared storage.
     */
    private void stubStorage() {
        doReturn("minio://bucket/key").when(mediaService).upload(any(), any(), any());
        doReturn("http://minio.test/signed").when(mediaService).resolveUrl(any());
    }

    /** An INSTRUCTOR belonging to {@code org} - the role is org-scoped here. */
    private User instructorIn(Organization org) {
        User user = new User();
        user.setEmail("test-instructor@bvisionry.invalid");
        user.setName("Test Instructor");
        user.setRole(UserRole.INSTRUCTOR);
        user.setStatus(UserStatus.ACTIVE);
        user.setOrganization(org);
        User saved = userRepository.save(user);
        TestAuthentication.authenticate(saved);
        return saved;
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

    private MockMultipartFile file(String name, String contentType) {
        return new MockMultipartFile("file", name, contentType, new byte[] {1, 2, 3});
    }

    private org.springframework.test.web.servlet.RequestBuilder multipart(
            String kind, UUID orgId, MockMultipartFile file) {
        var request = MockMvcRequestBuilders.multipart("/api/v1/media").file(file).param("kind", kind);
        if (orgId != null) {
            request.param("orgId", orgId.toString());
        }
        return request;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder presign(
            String kind, UUID orgId, String contentType) {
        var request = post("/api/v1/media/presign")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"filename": "logo.png", "contentType": "%s", "kind": "%s"}
                        """.formatted(contentType, kind));
        return orgId == null ? request : request.param("orgId", orgId.toString());
    }

    // ------------------------------------------------ ORG_ADMIN is admitted…

    @Test
    void orgAdminWithNoOrgIdIsRefusedOnBothPaths() throws Exception {
        TestAuthentication.authenticateAsOrgAdmin(userRepository, own);

        mockMvc.perform(multipart("image", null, file("logo.png", "image/png")))
                .andExpect(status().isForbidden());
        mockMvc.perform(presign("image", null, "image/png"))
                .andExpect(status().isForbidden());
    }

    @Test
    void orgAdminCannotUploadIntoAnotherOrgsPrefix() throws Exception {
        TestAuthentication.authenticateAsOrgAdmin(userRepository, own);

        mockMvc.perform(multipart("image", other.getId(), file("logo.png", "image/png")))
                .andExpect(status().isForbidden());
        mockMvc.perform(presign("image", other.getId(), "image/png"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------ …and pinned to images

    @Test
    void orgAdminCannotUploadAnythingButAnImage() throws Exception {
        TestAuthentication.authenticateAsOrgAdmin(userRepository, own);

        // 400, not 403: authorization admitted the caller, the kind rule refused
        // the request. Both codes must be reachable or the two layers are
        // indistinguishable and one of them could be missing.
        mockMvc.perform(multipart("pdf", own.getId(), file("x.pdf", "application/pdf")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(presign("video", own.getId(), "video/mp4"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(presign("asset", own.getId(), "application/zip"))
                .andExpect(status().isBadRequest());
    }

    /**
     * The control for every 403 above: the SAME caller, the SAME org, an image
     * kind — refused only by the content-type allowlist, which is downstream of
     * authorization. Without this, an expression that locked ORG_ADMIN out
     * entirely would pass every other test in this class.
     */
    @Test
    void orgAdminIsAdmittedForImagesAndStopsAtTheContentTypeAllowlist() throws Exception {
        TestAuthentication.authenticateAsOrgAdmin(userRepository, own);

        mockMvc.perform(presign("image", own.getId(), "image/svg+xml"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("image", own.getId(), file("evil.html", "text/html")))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- wiring

    /**
     * The org id the caller was authorized for is the one the service receives.
     * Without this, hardcoding a foreign UUID at the call site leaves every
     * other test in this class green while every uploaded logo lands under
     * another tenant's prefix.
     */
    @Test
    void theAuthorizedOrgIdIsTheOneHandedToTheService() throws Exception {
        TestAuthentication.authenticateAsOrgAdmin(userRepository, own);
        stubStorage();

        mockMvc.perform(multipart("image", own.getId(), file("logo.png", "image/png")))
                .andExpect(status().isCreated());

        verify(mediaService).upload(any(), eq("image"), eq(own.getId()));
    }

    /** The platform path must keep passing a NULL org id, or its keys move. */
    @Test
    void thePlatformPathStillPassesNoOrgId() throws Exception {
        TestAuthentication.authenticateAsSuperAdmin(userRepository);
        stubStorage();

        mockMvc.perform(multipart("video", null, file("clip.mp4", "video/mp4")))
                .andExpect(status().isCreated());

        verify(mediaService).upload(any(), eq("video"), eq((UUID) null));
    }

    // ------------------------------------------------------------- everyone else

    @Test
    void aMemberOfTheOrgIsStillRefused() throws Exception {
        TestAuthentication.authenticateAsMember(userRepository, own);

        mockMvc.perform(multipart("image", own.getId(), file("logo.png", "image/png")))
                .andExpect(status().isForbidden());
        mockMvc.perform(presign("image", own.getId(), "image/png"))
                .andExpect(status().isForbidden());
    }

    /** The platform path keeps its old behaviour: no orgId, no kind restriction. */
    @Test
    void aSuperAdminIsUnaffectedByTheOrgScopedRules() throws Exception {
        TestAuthentication.authenticateAsSuperAdmin(userRepository);

        // Admitted with no orgId and a non-image kind — only the allowlist refuses.
        mockMvc.perform(presign("video", null, "text/html"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------- INSTRUCTOR tenancy

    /**
     * INSTRUCTOR IS AN ORG-SCOPED ROLE, and this is the falsifier for the
     * expression that makes it so.
     *
     * <p>An ORG_ADMIN can invite an instructor ({@code InvitationService}), and
     * {@code QuizService} already carries the warning that a bare role check is
     * org-agnostic. Written the obvious way -
     * {@code hasAnyAuthority('SUPER_ADMIN','INSTRUCTOR') or (ORG_ADMIN and isInOrg)} -
     * the role branch SHORT-CIRCUITS before the tenancy predicate is ever
     * evaluated, so an instructor in org A could POST with {@code orgId=<orgB>}
     * and write into another tenant's branding namespace, receiving a valid
     * marker and a presigned URL for it. That is precisely the invariant the
     * branding IDOR guard rests on.
     */
    @Test
    void anInstructorCannotUploadIntoAnotherOrgsPrefix() throws Exception {
        instructorIn(own);

        mockMvc.perform(multipart("image", other.getId(), file("logo.png", "image/png")))
                .andExpect(status().isForbidden());
        mockMvc.perform(presign("image", other.getId(), "image/png"))
                .andExpect(status().isForbidden());
        verify(mediaService, never()).upload(any(), any(), any());
    }

    /**
     * ORG-SCOPED uploads are an ORG_ADMIN concern. An instructor is not one, so
     * even their OWN org is refused - the branding surface has a single writer
     * role, and widening it here would silently widen who can change an org's
     * identity.
     */
    @Test
    void anInstructorCannotUploadIntoTheirOwnOrgsBrandingPrefixEither() throws Exception {
        instructorIn(own);

        mockMvc.perform(multipart("image", own.getId(), file("logo.png", "image/png")))
                .andExpect(status().isForbidden());
        mockMvc.perform(presign("image", own.getId(), "image/png"))
                .andExpect(status().isForbidden());
    }

    /** The historical lesson-media path an instructor DOES own is untouched. */
    @Test
    void anInstructorKeepsThePlatformPath() throws Exception {
        instructorIn(own);
        stubStorage();

        mockMvc.perform(multipart("video", null, file("clip.mp4", "video/mp4")))
                .andExpect(status().isCreated());
        verify(mediaService).upload(any(), eq("video"), eq((UUID) null));
    }
}
