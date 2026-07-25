package com.bvisionry.catalog;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.bvisionry.auth.entity.User;
import com.bvisionry.catalog.domain.Course;
import com.bvisionry.catalog.domain.Section;
import com.bvisionry.catalog.dto.authoring.UpsertSectionRequest;
import com.bvisionry.catalog.repository.ContentRepository;
import com.bvisionry.catalog.repository.CourseRepository;
import com.bvisionry.catalog.repository.SectionRepository;
import com.bvisionry.catalog.repository.TagRepository;
import com.bvisionry.catalog.web.AuthoringService;
import com.bvisionry.catalog.web.CourseMapper;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.organization.entity.Organization;

import static com.bvisionry.testsupport.TestAuthentication.authenticate;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the org-ownership gate on authoring writes. The controller is only
 * ROLE-gated (SUPER_ADMIN / INSTRUCTOR); org scoping happens in the service
 * via {@code SecurityUtils.requireOrgAccess} — an INSTRUCTOR of one org must
 * never be able to author on another org's course. One representative write
 * (createSection) exercises the shared guard; every other write in
 * {@code AuthoringService} routes through the same call.
 */
@ExtendWith(MockitoExtension.class)
class AuthoringServiceOrgScopingTest {

    @Mock private CourseRepository courses;
    @Mock private SectionRepository sections;
    @Mock private ContentRepository contents;
    @Mock private TagRepository tags;
    @Mock private CourseMapper mapper;

    private AuthoringService service;

    private final UUID myOrgId = UUID.randomUUID();
    private final UUID foreignOrgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AuthoringService(courses, sections, contents, tags, mapper);
        lenient().when(sections.save(any(Section.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void instructorCannotAuthorOnForeignOrgCourse() {
        authenticateInstructorOf(myOrgId);
        stubCourse("foreign-course", foreignOrgId);

        assertThatThrownBy(() ->
                service.createSection("foreign-course", new UpsertSectionRequest("Week 1", 0)))
                .isInstanceOf(AccessDeniedException.class);
        verify(sections, never()).save(any());
    }

    @Test
    void instructorCanAuthorOnOwnOrgCourse() {
        authenticateInstructorOf(myOrgId);
        stubCourse("my-course", myOrgId);

        assertThatCode(() ->
                service.createSection("my-course", new UpsertSectionRequest("Week 1", 0)))
                .doesNotThrowAnyException();
        verify(sections).save(any(Section.class));
    }

    @Test
    void superAdminCanAuthorAcrossOrgs() {
        User admin = new User();
        admin.setId(UUID.randomUUID());
        admin.setRole(UserRole.SUPER_ADMIN);
        authenticate(admin);
        stubCourse("any-course", foreignOrgId);

        assertThatCode(() ->
                service.createSection("any-course", new UpsertSectionRequest("Week 1", 0)))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private void authenticateInstructorOf(UUID orgId) {
        Organization org = new Organization();
        ReflectionTestUtils.setField(org, "id", orgId);
        User instructor = new User();
        instructor.setId(UUID.randomUUID());
        instructor.setRole(UserRole.INSTRUCTOR);
        instructor.setOrganization(org);
        authenticate(instructor);
    }

    private void stubCourse(String slug, UUID orgId) {
        Course course = new Course();
        ReflectionTestUtils.setField(course, "id", UUID.randomUUID());
        course.setSlug(slug);
        course.setOrgId(orgId);
        when(courses.findBySlug(slug)).thenReturn(Optional.of(course));
    }
}
