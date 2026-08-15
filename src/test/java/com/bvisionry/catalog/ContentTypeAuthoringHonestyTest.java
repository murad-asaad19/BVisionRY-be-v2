package com.bvisionry.catalog;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.bvisionry.auth.entity.User;
import com.bvisionry.catalog.domain.Content;
import com.bvisionry.catalog.domain.ContentType;
import com.bvisionry.catalog.domain.Course;
import com.bvisionry.catalog.domain.Section;
import com.bvisionry.catalog.dto.authoring.UpsertContentRequest;
import com.bvisionry.catalog.repository.ContentRepository;
import com.bvisionry.catalog.repository.CourseRepository;
import com.bvisionry.catalog.repository.SectionRepository;
import com.bvisionry.catalog.repository.TagRepository;
import com.bvisionry.catalog.web.AuthoringService;
import com.bvisionry.catalog.web.CourseMapper;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.exception.BadRequestException;

import static com.bvisionry.testsupport.TestAuthentication.authenticate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Authoring honesty: an author may only create lesson types the player can
 * actually play, and retiring a type must never break the rows that already
 * carry it.
 *
 * <p>{@link BadRequestException} is mapped to 400 by {@code GlobalExceptionHandler};
 * before this change an unauthorable/unknown type reached {@code Enum.valueOf}
 * and surfaced as a 500 through the catch-all.
 */
@ExtendWith(MockitoExtension.class)
class ContentTypeAuthoringHonestyTest {

    @Mock private CourseRepository courses;
    @Mock private SectionRepository sections;
    @Mock private ContentRepository contents;
    @Mock private TagRepository tags;
    @Mock private org.springframework.beans.factory.ObjectProvider<com.bvisionry.common.security.OrgHierarchyPort> hierarchyProvider;

    private AuthoringService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID sectionId = UUID.randomUUID();
    private final UUID contentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Real mapper: the hydration test asserts what a retired row maps to.
        service = new AuthoringService(courses, sections, contents, tags, new CourseMapper(),
                new com.bvisionry.config.SecurityContextOrgScope(hierarchyProvider),
                new com.bvisionry.config.SecurityContextCurrentUserAccessor());
        User admin = new User();
        admin.setId(UUID.randomUUID());
        admin.setRole(UserRole.SUPER_ADMIN);
        authenticate(admin);
        lenient().when(contents.save(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // -------------------------------------------------------------------------
    // Write path — retired types are rejected
    // -------------------------------------------------------------------------

    @Test
    void createContentRejectsEveryRetiredType() {
        stubSection();
        for (ContentType retired : retiredTypes()) {
            assertThatThrownBy(() -> service.createContent(sectionId.toString(), request(retired.name())))
                    .as("create %s", retired)
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("retired");
        }
        assertThat(retiredTypes()).containsExactlyInAnyOrder(
                ContentType.SCORM, ContentType.WEBPAGE, ContentType.DOCUMENT, ContentType.IMAGE);
        verify(contents, never()).save(any());
    }

    @Test
    void updateContentRejectsEveryRetiredType() {
        stubExistingContent(ContentType.VIDEO);
        for (ContentType retired : retiredTypes()) {
            assertThatThrownBy(() -> service.updateContent(contentId.toString(), request(retired.name())))
                    .as("update %s", retired)
                    .isInstanceOf(BadRequestException.class);
        }
        verify(contents, never()).save(any());
    }

    @Test
    void unknownTypeIsARejectedRequestNotAServerError() {
        stubSection();
        assertThatThrownBy(() -> service.createContent(sectionId.toString(), request("HOLOGRAM")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unknown lesson type");
    }

    @Test
    void everyAuthorableTypeIsStillAccepted() {
        stubSection();
        for (ContentType authorable : ContentType.authorable()) {
            assertThatCode(() -> service.createContent(sectionId.toString(), request(authorable.name())))
                    .as("create %s", authorable)
                    .doesNotThrowAnyException();
        }
        // The offered set is exactly what content-viewer.tsx dispatches on.
        assertThat(ContentType.authorable()).containsExactlyInAnyOrder(
                ContentType.VIDEO, ContentType.ARTICLE, ContentType.QUIZ, ContentType.ASSIGNMENT,
                ContentType.PDF, ContentType.CERTIFICATION, ContentType.PAGE, ContentType.LINK);
    }

    // -------------------------------------------------------------------------
    // Read path — retiring a type must not break the rows that carry it
    // -------------------------------------------------------------------------

    /**
     * The enum-hydration trap: {@code @Enumerated(STRING)} throws when a stored
     * value has no constant. {@code ck_content_type} is the exhaustive list of
     * values a row may legally hold, and narrowing it is a forbidden contraction
     * migration — so every name in it must keep a constant here, forever.
     */
    @Test
    void everyValueTheDatabaseAllowsStillHasAConstant() {
        List<String> allowed = contentTypeCheckConstraintValues();
        assertThat(allowed).contains("SCORM", "WEBPAGE", "DOCUMENT", "IMAGE", "ARTICLE");
        for (String name : allowed) {
            assertThatCode(() -> ContentType.valueOf(name))
                    .as("ck_content_type allows '%s' — deleting that constant 500s on hydration", name)
                    .doesNotThrowAnyException();
        }
    }

    /**
     * V77__catalog_seed.sql inserts two SCORM lessons, so this row shape exists in
     * every seeded database. It must still load and map for the authoring editor.
     */
    @Test
    void aLegacyScormRowStillHydratesAndMaps() {
        Course course = new Course();
        ReflectionTestUtils.setField(course, "id", UUID.randomUUID());
        course.setSlug("data-analysis-with-sql");
        course.setOrgId(orgId);
        when(courses.findBySlug("data-analysis-with-sql")).thenReturn(Optional.of(course));

        Section section = section();
        Content legacy = content(ContentType.SCORM);
        legacy.setSection(section);
        section.getContents().add(legacy);
        when(sections.findByCourseIdWithContents(course.getId())).thenReturn(new ArrayList<>(List.of(section)));

        var detail = service.getForEditing("data-analysis-with-sql");

        assertThat(detail.sections()).hasSize(1);
        assertThat(detail.sections().getFirst().lessons())
                .extracting(l -> l.contentType())
                .containsExactly("SCORM");
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private static List<ContentType> retiredTypes() {
        return Arrays.stream(ContentType.values()).filter(t -> !t.isAuthorable()).toList();
    }

    private static UpsertContentRequest request(String contentType) {
        return new UpsertContentRequest("Lesson", contentType, 0, 10, false, null, null, null, null);
    }

    private void stubSection() {
        when(sections.findById(sectionId)).thenReturn(Optional.of(section()));
    }

    private Section section() {
        Section s = new Section();
        ReflectionTestUtils.setField(s, "id", sectionId);
        s.setOrgId(orgId);
        s.setTitle("Week 1");
        return s;
    }

    private void stubExistingContent(ContentType current) {
        when(contents.findById(contentId)).thenReturn(Optional.of(content(current)));
    }

    private Content content(ContentType type) {
        Content c = new Content();
        ReflectionTestUtils.setField(c, "id", contentId);
        c.setOrgId(orgId);
        c.setTitle("Hands-on lab: sales data");
        c.setContentType(type);
        return c;
    }

    /** The {@code ck_content_type} value list, read straight out of the migration. */
    private static List<String> contentTypeCheckConstraintValues() {
        String sql;
        try (var in = ContentTypeAuthoringHonestyTest.class
                .getResourceAsStream("/db/migration/V76__catalog_schema.sql")) {
            assertThat(in).as("V76__catalog_schema.sql on the classpath").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read V76__catalog_schema.sql", e);
        }
        Matcher block = Pattern
                .compile("CONSTRAINT\\s+ck_content_type\\s+CHECK\\s*\\(content_type IN\\s*\\((.*?)\\)",
                        Pattern.DOTALL)
                .matcher(sql);
        assertThat(block.find()).as("ck_content_type CHECK found in V76").isTrue();
        List<String> values = new ArrayList<>();
        Matcher name = Pattern.compile("'([A-Z_]+)'").matcher(block.group(1));
        while (name.find()) {
            values.add(name.group(1));
        }
        assertThat(values).as("parsed ck_content_type values").isNotEmpty();
        return values;
    }
}
