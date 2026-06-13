package com.bvisionry.catalog.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bvisionry.catalog.domain.Course;
import com.bvisionry.catalog.domain.CourseAudience;
import com.bvisionry.catalog.domain.CourseLevel;
import com.bvisionry.catalog.dto.CourseDetailDto;
import com.bvisionry.catalog.dto.CourseListResponse;
import com.bvisionry.catalog.dto.CourseSummaryDto;
import com.bvisionry.catalog.repository.CourseRepository;

/**
 * Read-side application service for the public course catalog.
 *
 * <p>The catalog is FULLY PUBLIC — no tenant/org scoping is applied. All
 * PUBLISHED courses across all organisations are visible. Filters (q, category,
 * level, mode/audience, maxPrice) narrow the result set but never restrict by
 * org. Unknown enum values for {@code level} / {@code mode} yield an empty
 * result rather than an error, keeping the endpoint forgiving of stale
 * frontend query strings.
 */
@Service
public class CatalogService {

    private final CourseRepository courses;
    private final CourseMapper mapper;

    public CatalogService(CourseRepository courses, CourseMapper mapper) {
        this.courses = courses;
        this.mapper = mapper;
    }

    /**
     * Lists all published catalog courses matching the given filters.
     * All filters are optional (blank/null = ignore).
     */
    @Transactional(readOnly = true)
    public CourseListResponse list(String q, String category, String level,
            String mode, BigDecimal maxPrice) {

        CourseLevel levelEnum = parseEnum(CourseLevel.class, level);
        CourseAudience audienceEnum = parseEnum(CourseAudience.class, mode);

        // A provided-but-invalid enum filter can never match → empty page.
        if ((StringUtils.hasText(level) && levelEnum == null)
                || (StringUtils.hasText(mode) && audienceEnum == null)) {
            return new CourseListResponse(List.of(), 0);
        }

        String categoryLower = lowerOrNull(category);
        String qLike = toLikePattern(q);

        List<Course> found = (qLike == null)
                ? courses.findCatalog(categoryLower, levelEnum, audienceEnum, maxPrice)
                : courses.findCatalogSearch(qLike, categoryLower, levelEnum, audienceEnum, maxPrice);

        List<CourseSummaryDto> items = found.stream().map(mapper::toSummary).toList();
        return new CourseListResponse(items, items.size());
    }

    /**
     * Loads a single published course detail by slug.
     *
     * <p>Hydrates the aggregate with five targeted queries that all return the
     * same managed entity within this transaction (sections, then section
     * contents, reviews, tags, outcomes) — see {@link CourseRepository} javadoc
     * on why the bags cannot be fetch-joined together.
     *
     * @throws CourseNotFoundException if no published course has that slug.
     */
    @Transactional(readOnly = true)
    public CourseDetailDto getBySlug(String slug) {
        Course course = courses.findDetailBySlug(slug)
                .orElseThrow(() -> new CourseNotFoundException(slug));

        // Hydrate the remaining bags onto the same managed instance.
        courses.fetchSectionContents(course.getId());
        courses.fetchReviews(course.getId());
        courses.fetchTags(course.getId());
        courses.fetchOutcomes(course.getId());

        return mapper.toDetail(course);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Trims and lowercases for case-insensitive equality, or {@code null} if blank. */
    private static String lowerOrNull(String s) {
        return StringUtils.hasText(s) ? s.trim().toLowerCase(Locale.ROOT) : null;
    }

    /**
     * Builds the lowercased {@code %…%} LIKE pattern for free-text search, or
     * {@code null} when there is no query.
     *
     * <p>The term is pre-lowercased so the matching column side uses
     * {@code LOWER(...)} while the bind stays a plain string (no
     * {@code lower(:param)}, which Postgres mis-types as {@code bytea}).
     */
    private static String toLikePattern(String q) {
        if (!StringUtils.hasText(q)) {
            return null;
        }
        return "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
    }

    /** Case-insensitive enum parse; returns {@code null} for blank/unknown. */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
