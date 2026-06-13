package com.bvisionry.catalog.web;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.bvisionry.catalog.domain.Content;
import com.bvisionry.catalog.domain.Course;
import com.bvisionry.catalog.domain.Review;
import com.bvisionry.catalog.domain.Section;
import com.bvisionry.catalog.domain.Tag;
import com.bvisionry.catalog.dto.AdminCourseDetailDto;
import com.bvisionry.catalog.dto.CourseAdminDto;
import com.bvisionry.catalog.dto.CourseDetailDto;
import com.bvisionry.catalog.dto.CourseSummaryDto;

/**
 * Maps {@link Course} aggregates to the API-contract DTOs.
 *
 * <p>Most enum fields serialise 1:1 by name ({@code level}, {@code enrollPolicy},
 * lesson {@code type}). The contract's {@code mode} is sourced from
 * {@link Course#getAudience()} and {@code visibility} from
 * {@link Course#getAccess()} — these are stored in their own columns precisely
 * so the mapping is a direct {@code .name()} with no lossy translation.
 *
 * <p>Instructor display data is read from the denormalized columns
 * ({@link Course#getInstructorName()}, {@link Course#getInstructorTitle()},
 * {@link Course#getInstructorBio()}) — no join to the identity {@code users}
 * table is required.
 *
 * <p>This bean is stateless; callers must ensure any lazy associations it reads
 * (sections, contents, reviews, tags, outcomes) are already initialised within
 * the active transaction.
 */
@Component
public class CourseMapper {

    /** Maps a course to the catalog list item. */
    public CourseSummaryDto toSummary(Course c) {
        return new CourseSummaryDto(
                c.getId().toString(),
                c.getSlug(),
                c.getTitle(),
                c.getSubtitle(),
                c.getCategory(),
                c.getLevel().name(),
                c.getAudience().name(),
                c.getPrice(),
                c.getCurrency(),
                c.getRating(),
                c.getReviewsCount(),
                c.getLearnersCount(),
                c.getLessonsCount(),
                c.getDurationHours(),
                nullToEmpty(c.getInstructorName()),
                tagNames(c.getTags()),
                c.getEnrollPolicy().name(),
                c.getAccess().name(),
                c.getCoverGradient());
    }

    /** Maps a fully-hydrated course to the detail view. */
    public CourseDetailDto toDetail(Course c) {
        return new CourseDetailDto(
                c.getId().toString(),
                c.getSlug(),
                c.getTitle(),
                c.getSubtitle(),
                c.getCategory(),
                c.getLevel().name(),
                c.getAudience().name(),
                c.getPrice(),
                c.getCurrency(),
                c.getRating(),
                c.getReviewsCount(),
                c.getLearnersCount(),
                c.getLessonsCount(),
                c.getDurationHours(),
                nullToEmpty(c.getInstructorName()),
                tagNames(c.getTags()),
                c.getEnrollPolicy().name(),
                c.getAccess().name(),
                c.getCoverGradient(),
                c.getDescription(),
                List.copyOf(c.getOutcomes()),
                toInstructor(c),
                c.getSections().stream().map(this::toSection).toList(),
                c.getReviews().stream().map(this::toReview).toList(),
                toCertification(c));
    }

    /**
     * Maps a course to the authoring/admin list item — includes lifecycle
     * {@code state} and the supplied section/lesson counts (passed in because
     * the bags are lazy and the caller counts them with cheap COUNT queries).
     */
    public CourseAdminDto toAdminDto(Course c, int sectionsCount, int lessonsCount) {
        return new CourseAdminDto(
                c.getId().toString(),
                c.getSlug(),
                c.getTitle(),
                c.getSubtitle(),
                c.getCategory(),
                c.getLevel().name(),
                c.getMode().name(),
                c.getAudience().name(),
                c.getAccess().name(),
                c.getState().name(),
                c.getPrice(),
                c.getCurrency(),
                c.getDurationHours(),
                lessonsCount,
                c.getLearnersCount(),
                sectionsCount,
                nullToEmpty(c.getInstructorName()),
                c.getCoverGradient(),
                c.getCoverImageUrl(),
                tagNames(c.getTags()),
                c.getCreatedAt(),
                c.getUpdatedAt());
    }

    /**
     * Maps a course (+ its hydrated sections) to the full editable authoring
     * detail. {@code sections} must already have their {@code contents} loaded;
     * {@code course.outcomes}/{@code course.tags} must be initialised by the
     * caller (within the active transaction).
     */
    public AdminCourseDetailDto toAdminDetail(Course c, List<Section> sections) {
        return new AdminCourseDetailDto(
                c.getId().toString(),
                c.getSlug(),
                c.getTitle(),
                c.getSubtitle(),
                c.getCategory(),
                c.getDescription(),
                c.getLevel().name(),
                c.getMode().name(),
                c.getAudience().name(),
                c.getAccess().name(),
                c.getEnrollPolicy().name(),
                c.getVisibility().name(),
                c.getState().name(),
                c.getPrice(),
                c.getCurrency(),
                c.getDurationHours(),
                c.getCoverGradient(),
                c.getCoverImageUrl(),
                c.getCertificationTitle(),
                c.getCertificationPassingPct(),
                c.getInstructorName(),
                c.getInstructorTitle(),
                c.getInstructorBio(),
                List.copyOf(c.getOutcomes()),
                tagNames(c.getTags()),
                sections.stream().map(this::toAdminSection).toList(),
                c.getCreatedAt(),
                c.getUpdatedAt());
    }

    private AdminCourseDetailDto.Section toAdminSection(Section s) {
        return new AdminCourseDetailDto.Section(
                s.getId().toString(),
                s.getTitle(),
                s.getSequence(),
                s.getContents().stream().map(this::toAdminLesson).toList());
    }

    private AdminCourseDetailDto.Lesson toAdminLesson(Content content) {
        return new AdminCourseDetailDto.Lesson(
                content.getId().toString(),
                content.getTitle(),
                content.getContentType().name(),
                content.getSequence(),
                content.getDurationMin(),
                content.isAllowPreview(),
                content.getBody(),
                content.getVideoUrl(),
                content.getAssetUrl(),
                content.getPipelineId() == null ? null : content.getPipelineId().toString());
    }

    // -------------------------------------------------------------------------
    // Nested mappers
    // -------------------------------------------------------------------------

    private CourseDetailDto.Section toSection(Section s) {
        return new CourseDetailDto.Section(
                s.getId().toString(),
                s.getTitle(),
                s.getContents().stream().map(this::toLesson).toList());
    }

    private CourseDetailDto.Lesson toLesson(Content content) {
        return new CourseDetailDto.Lesson(
                content.getId().toString(),
                content.getTitle(),
                content.getContentType().name(),
                content.getDurationMin() == null ? 0 : content.getDurationMin(),
                content.isAllowPreview());
    }

    private CourseDetailDto.Review toReview(Review r) {
        return new CourseDetailDto.Review(
                r.getAuthorName(),
                r.getRating(),
                r.getComment());
    }

    /**
     * Builds an {@link CourseDetailDto.Instructor} from the denormalized columns
     * on the course entity — no join to the identity {@code users} table needed.
     */
    private CourseDetailDto.Instructor toInstructor(Course c) {
        return new CourseDetailDto.Instructor(
                nullToEmpty(c.getInstructorName()),
                nullToEmpty(c.getInstructorTitle()),
                nullToEmpty(c.getInstructorBio()));
    }

    private CourseDetailDto.Certification toCertification(Course c) {
        if (c.getCertificationTitle() == null) {
            return null;
        }
        int pct = c.getCertificationPassingPct() == null ? 0 : c.getCertificationPassingPct();
        return new CourseDetailDto.Certification(c.getCertificationTitle(), pct);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static List<String> tagNames(Set<Tag> tags) {
        return tags.stream().map(Tag::getName).sorted().toList();
    }
}
