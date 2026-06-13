package com.bvisionry.catalog.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.bvisionry.catalog.domain.Course;
import com.bvisionry.catalog.domain.CourseAudience;
import com.bvisionry.catalog.domain.CourseLevel;
import com.bvisionry.catalog.domain.Section;

/**
 * Read/write access to {@link Course}.
 *
 * <p><strong>Public catalog design:</strong> The list queries do NOT filter by
 * {@code org_id}; the public catalog returns ALL PUBLISHED courses regardless
 * of which org created them. Individual courses are accessible by slug globally.
 *
 * <p><strong>Loading a course detail by slug:</strong> Hibernate cannot
 * fetch-join more than one {@code List} (bag) collection in a single query
 * (it throws {@code MultipleBagFetchException}). So the detail is assembled
 * with four targeted queries that all return the <em>same</em> managed
 * {@code Course} instance within one persistence context:
 * <ol>
 *   <li>{@link #findDetailBySlug(String)} — course + sections,</li>
 *   <li>{@link #fetchSectionContents(UUID)} — hydrates section.contents,</li>
 *   <li>{@link #fetchReviews(UUID)} — hydrates course.reviews,</li>
 *   <li>{@link #fetchTags(UUID)} — hydrates course.tags,</li>
 *   <li>{@link #fetchOutcomes(UUID)} — hydrates course.outcomes.</li>
 * </ol>
 * Call all of these inside one {@code @Transactional} service method.
 */
@Repository
public interface CourseRepository extends JpaRepository<Course, UUID> {

    // -------------------------------------------------------------------------
    // Public catalog listing — no org scoping, all PUBLISHED courses.
    // -------------------------------------------------------------------------

    /**
     * Lists all published courses applying the optional catalog filters. Pass
     * {@code null} for any filter to ignore it. {@code maxPrice} also matches
     * free (price IS NULL) courses so "free" always shows up.
     *
     * <p>The search variant is {@link #findCatalogSearch}; kept separate so
     * this no-search path never binds a typeless {@code null} inside a
     * {@code LIKE}, which Postgres mis-infers as {@code bytea}.
     */
    @Query("""
            SELECT c FROM Course c
            WHERE c.state = com.bvisionry.catalog.domain.CourseState.PUBLISHED
              AND (:categoryLower IS NULL OR LOWER(c.category) = :categoryLower)
              AND (:level IS NULL OR c.level = :level)
              AND (:audience IS NULL OR c.audience = :audience)
              AND (:maxPrice IS NULL OR c.price IS NULL OR c.price <= :maxPrice)
            ORDER BY c.createdAt DESC
            """)
    List<Course> findCatalog(
            @Param("categoryLower") String categoryLower,
            @Param("level") CourseLevel level,
            @Param("audience") CourseAudience audience,
            @Param("maxPrice") BigDecimal maxPrice);

    /**
     * Same as {@link #findCatalog} but with a free-text search term. {@code qLike}
     * is the already-lowercased, {@code %…%}-wrapped, non-null search term matched
     * case-insensitively against title, subtitle, category and tag name.
     * {@code DISTINCT} de-duplicates the tag join.
     */
    @Query("""
            SELECT DISTINCT c FROM Course c
            LEFT JOIN c.tags t
            WHERE c.state = com.bvisionry.catalog.domain.CourseState.PUBLISHED
              AND (:categoryLower IS NULL OR LOWER(c.category) = :categoryLower)
              AND (:level IS NULL OR c.level = :level)
              AND (:audience IS NULL OR c.audience = :audience)
              AND (:maxPrice IS NULL OR c.price IS NULL OR c.price <= :maxPrice)
              AND (LOWER(c.title) LIKE :qLike
                   OR LOWER(c.subtitle) LIKE :qLike
                   OR LOWER(c.category) LIKE :qLike
                   OR LOWER(t.name) LIKE :qLike)
            ORDER BY c.createdAt DESC
            """)
    List<Course> findCatalogSearch(
            @Param("qLike") String qLike,
            @Param("categoryLower") String categoryLower,
            @Param("level") CourseLevel level,
            @Param("audience") CourseAudience audience,
            @Param("maxPrice") BigDecimal maxPrice);

    // -------------------------------------------------------------------------
    // Course detail by slug (5-step hydration; see class javadoc).
    // -------------------------------------------------------------------------

    /**
     * Loads a single published course by its globally unique slug together with
     * its sections. Section contents are a second bag and CANNOT be fetch-joined
     * here (Hibernate {@code MultipleBagFetchException}); hydrate them with
     * {@link #fetchSectionContents(UUID)} inside the same transaction.
     */
    @Query("""
            SELECT DISTINCT c FROM Course c
            LEFT JOIN FETCH c.sections
            WHERE c.slug = :slug
              AND c.state = com.bvisionry.catalog.domain.CourseState.PUBLISHED
            """)
    Optional<Course> findDetailBySlug(@Param("slug") String slug);

    /**
     * Hydrates {@code Section.contents} for every section of a course. The sections
     * returned here are the same managed instances loaded by
     * {@link #findDetailBySlug} (same persistence context), so their {@code contents}
     * bags become initialised on the already-loaded course's sections list.
     */
    @Query("""
            SELECT DISTINCT s FROM Section s
            LEFT JOIN FETCH s.contents
            WHERE s.course.id = :courseId
            """)
    List<Section> fetchSectionContents(@Param("courseId") UUID courseId);

    /**
     * Re-selects the same course by id, fetch-joining its reviews so they attach
     * to the already-managed entity returned by {@link #findDetailBySlug}.
     */
    @Query("""
            SELECT c FROM Course c
            LEFT JOIN FETCH c.reviews
            WHERE c.id = :courseId
            """)
    Optional<Course> fetchReviews(@Param("courseId") UUID courseId);

    /**
     * Re-selects the same course by id, fetch-joining its tags so they attach to
     * the already-managed entity returned by {@link #findDetailBySlug}.
     */
    @Query("""
            SELECT c FROM Course c
            LEFT JOIN FETCH c.tags
            WHERE c.id = :courseId
            """)
    Optional<Course> fetchTags(@Param("courseId") UUID courseId);

    /**
     * Re-selects the same course by id, fetch-joining its ordered outcomes so
     * they attach to the already-managed entity. Outcomes are an
     * {@code @ElementCollection} bag, hence a dedicated query.
     */
    @Query("""
            SELECT c FROM Course c
            LEFT JOIN FETCH c.outcomes
            WHERE c.id = :courseId
            """)
    Optional<Course> fetchOutcomes(@Param("courseId") UUID courseId);

    /** Plain lookup by slug (no associations fetched). */
    Optional<Course> findBySlug(String slug);

    /** Slug uniqueness check used when authoring new courses. */
    boolean existsBySlug(String slug);

    // -------------------------------------------------------------------------
    // Authoring listing — org-scoped, ALL states (DRAFT/PUBLISHED/ARCHIVED).
    // -------------------------------------------------------------------------

    /** All courses owned by one org, newest-edited first (authoring console, ORG_ADMIN/INSTRUCTOR scope). */
    List<Course> findByOrgIdOrderByUpdatedAtDesc(UUID orgId);

    /** Every course across all orgs, newest-edited first (SUPER_ADMIN authoring console). */
    List<Course> findAllByOrderByUpdatedAtDesc();
}
