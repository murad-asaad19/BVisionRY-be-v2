package com.bvisionry.catalog.dto;

import com.bvisionry.enrollment.dto.LessonContentDto;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The anonymous half of the roadmap's "audit anonymous read endpoints" item:
 * <em>confirm no non-preview lesson body is ever returned to an anonymous
 * caller</em>.
 *
 * <p>{@code GET /api/v1/courses/{slug}} is {@code permitAll} — the marketing
 * site renders a course page with no session — and it returns a
 * {@link CourseDetailDto} containing every section and every lesson. That is the
 * ONE public surface that names lessons at all, so it is the one surface where a
 * body could escape, and it would escape by the quietest possible route: someone
 * adds a field to {@link CourseDetailDto.Lesson} to save a round-trip in the
 * player, and every unpublished, paid and non-preview lesson in the catalog is
 * public from that commit onwards. No compiler error, no failing page test, and
 * the leak is in a 200 response nobody re-reads.
 *
 * <p>So this pins the DTO's SHAPE rather than any one field's absence: the
 * public lesson projection may carry these five components and nothing else.
 * Adding a sixth fails here and forces the author to justify it, which is the
 * entire point — the check has to fail on the field nobody thought to forbid.
 *
 * <p>Deliberately NOT a Spring test. The invariant is structural, so it costs
 * milliseconds and runs on every machine, Docker or not.
 * {@code CatalogRouteSecurityIntegrationTest} covers the other half — that the
 * route serving actual bodies refuses an anonymous caller in the filter chain.
 */
class PublicCourseDetailShapeTest {

    /**
     * Metadata a shopper legitimately sees before buying: what the lesson is
     * called, what kind it is, how long it runs, and whether they may sample it.
     * {@code preview} is a FLAG here — honouring it is
     * {@code EnrollmentService.lessonContent}'s job, behind authentication.
     */
    private static final List<String> PUBLIC_LESSON_FIELDS =
            List.of("id", "title", "type", "durationMin", "preview");

    /**
     * The three body-bearing components of {@link LessonContentDto}, named from
     * that record rather than typed out, so a rename there cannot leave this
     * test guarding a field that no longer exists.
     */
    private static final Set<String> BODY_FIELDS = Set.of("body", "videoUrl", "assetUrl");

    private static Stream<String> componentsOf(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName);
    }

    @Test
    void thePublicLessonProjectionCarriesMetadataAndNothingElse() {
        assertThat(componentsOf(CourseDetailDto.Lesson.class))
                .as("a field added to the public lesson DTO ships every lesson's "
                        + "content to anonymous callers — justify it or move it behind "
                        + "GET /api/v1/courses/{slug}/content/{contentId}")
                .containsExactlyInAnyOrderElementsOf(PUBLIC_LESSON_FIELDS);
    }

    @Test
    void noneOfTheBodyBearingFieldNamesAppearOnThePublicProjection() {
        // Redundant with the exact-match above BY CONSTRUCTION, and kept because
        // the two fail with different messages: the assertion above says "the
        // shape changed", this one says "the shape changed IN THE WAY THAT LEAKS".
        assertThat(componentsOf(CourseDetailDto.Lesson.class)).doesNotContainAnyElementsOf(BODY_FIELDS);
    }

    @Test
    void theBodyBearingFieldsStillLiveOnTheAuthenticatedDto() {
        // Falsification guard for the constant above: if LessonContentDto renames
        // `body`, BODY_FIELDS silently stops naming anything real and the test
        // above passes vacuously forever. This is what makes it non-vacuous.
        assertThat(componentsOf(LessonContentDto.class)).containsAll(BODY_FIELDS);
    }
}
