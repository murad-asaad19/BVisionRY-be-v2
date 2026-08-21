package com.bvisionry.courseaccess.repository;

import com.bvisionry.common.coursevisibility.CourseVisibilityAccess;
import com.bvisionry.common.enums.EnrollmentSource;
import com.bvisionry.common.progress.CourseProgressSql;
import com.bvisionry.courseaccess.domain.EffectiveCourses.CourseMeta;
import com.bvisionry.courseaccess.domain.EffectiveCourses.EnrolmentRow;
import com.bvisionry.courseaccess.domain.EffectiveCourses.RuleRow;
import com.bvisionry.courseaccess.domain.EffectiveCourses.SuggestionRow;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every cross-slice READ the course-wiring surfaces need, in raw SQL.
 *
 * <p>Raw SQL rather than the owning slices' services for the usual reason: the
 * ArchUnit ratchet forbids new feature→feature edges, so this depends on the
 * SCHEMA (enrollment, course, auto_enrolments, enrolment_overrides, users) and
 * imports no type from any of them. Same pattern as
 * {@code coaching.CoachingReadRepository} and
 * {@code pipeline.CourseCatalogReadRepository}.
 *
 * <p><strong>CANCELLED is invisible everywhere here.</strong> An admin removal
 * flips status rather than deleting, so every read below spells "has this
 * course" as {@code status <> 'CANCELLED'} — matching
 * {@code EnrollmentRepository}'s own filtered methods.
 */
@Repository
public class CourseAccessReadRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CourseAccessReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ------------------------------------------------ the member's three paths */

    /** Real enrollment rows, whatever put them there. */
    public List<EnrolmentRow> enrolments(UUID userId) {
        return jdbc.query("""
                SELECT e.course_id, e.source, e.status, %1$s AS progress_pct, e.required,
                       e.deadline, e.enrolled_at, e.completed_at, ab.name AS assigned_by_name
                  FROM enrollment e
                  LEFT JOIN users ab ON ab.id = e.assigned_by
                 WHERE e.user_id = :userId AND e.status <> 'CANCELLED'
                """.formatted(CourseProgressSql.LIVE_PROGRESS_PCT),
                new MapSqlParameterSource("userId", userId),
                (rs, i) -> new EnrolmentRow(
                        rs.getObject("course_id", UUID.class),
                        EnrollmentSource.of(rs.getString("source")),
                        rs.getString("status"),
                        rs.getInt("progress_pct"),
                        rs.getBoolean("required"),
                        instant(rs, "deadline"),
                        instant(rs, "enrolled_at"),
                        instant(rs, "completed_at"),
                        rs.getString("assigned_by_name")));
    }

    /**
     * The org rules covering this member, minus their exclusions.
     *
     * <p>This is what "evaluated at read time" means: a member who joined the
     * org this morning is covered by every rule without a backfill, and deleting
     * the rule uncovers everyone at once.
     *
     * <p>Filtered by the SAME predicate {@code accept()} uses (PUBLISHED and
     * visible), because an UN-materialized claim is an offer: showing one the
     * member would be refused is a dead card. Materialized rows are deliberately
     * NOT filtered — spec §3's downgrade policy keeps progress visible.
     */
    public List<RuleRow> rules(UUID orgId, UUID userId) {
        if (orgId == null) {
            return List.of();
        }
        return jdbc.query("""
                SELECT r.course_id, r.required, r.deadline, r.created_at,
                       COALESCE(ub.name, cb.name) AS created_by_name
                  FROM org_course_rules r
                  JOIN course c ON c.id = r.course_id
                  LEFT JOIN users cb ON cb.id = r.created_by
                  LEFT JOIN users ub ON ub.id = r.updated_by
                 WHERE r.org_id = :orgId
                   AND c.state = 'PUBLISHED' AND %s
                   AND NOT EXISTS (SELECT 1 FROM enrolment_overrides o
                                    WHERE o.user_id = :userId AND o.course_id = r.course_id)
                """.formatted(CourseVisibilityAccess.VISIBLE_TO_ORG.formatted(":orgId")),
                new MapSqlParameterSource("orgId", orgId).addValue("userId", userId),
                (rs, i) -> new RuleRow(
                        rs.getObject("course_id", UUID.class),
                        rs.getBoolean("required"),
                        instant(rs, "deadline"),
                        instant(rs, "created_at"),
                        rs.getString("created_by_name")));
    }

    /**
     * Open AI suggestions — Suggest-mode ledger rows nobody has accepted and no
     * admin has excluded. Newest decision per course wins.
     */
    public List<SuggestionRow> suggestions(UUID userId) {
        return jdbc.query("""
                SELECT DISTINCT ON (a.course_id) a.course_id, a.created_at, p.name AS pillar_name
                  FROM auto_enrolments a
                  JOIN pillars p ON p.id = a.pillar_id
                  JOIN course c ON c.id = a.course_id
                  JOIN users u ON u.id = a.user_id
                 WHERE a.user_id = :userId
                   AND a.outcome = 'SUGGESTED'
                   AND a.accepted_at IS NULL
                   AND c.state = 'PUBLISHED' AND %s
                   AND NOT EXISTS (SELECT 1 FROM enrolment_overrides o
                                    WHERE o.user_id = :userId AND o.course_id = a.course_id)
                 ORDER BY a.course_id, a.created_at DESC, a.id ASC
                """.formatted(
                        CourseVisibilityAccess.VISIBLE_TO_ORG.formatted("u.organization_id")),
                new MapSqlParameterSource("userId", userId),
                (rs, i) -> new SuggestionRow(
                        rs.getObject("course_id", UUID.class),
                        instant(rs, "created_at"),
                        rs.getString("pillar_name")));
    }

    /** Titles for whatever course ids the three reads mentioned. Any state — a course pulled from the catalog must still name itself on a member's shelf. */
    public Map<UUID, CourseMeta> courseMeta(Collection<UUID> courseIds) {
        if (courseIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.query("SELECT id, title, slug FROM course WHERE id IN (:ids)",
                        new MapSqlParameterSource("ids", List.copyOf(courseIds)),
                        (rs, i) -> new CourseMeta(rs.getObject("id", UUID.class),
                                rs.getString("title"), rs.getString("slug")))
                .stream()
                .collect(Collectors.toMap(CourseMeta::courseId, Function.identity(), (a, b) -> a));
    }

    public UUID orgIdOf(UUID userId) {
        return jdbc.query("SELECT organization_id FROM users WHERE id = :userId",
                        new MapSqlParameterSource("userId", userId),
                        (rs, i) -> rs.getObject("organization_id", UUID.class))
                .stream().findFirst().orElse(null);
    }

    /** The catalog an org's members may browse: PUBLISHED ∩ visible. */
    public record CatalogRow(UUID courseId, String title, String slug, String category,
                             String level, Integer lessonsCount) {}

    public List<CatalogRow> visibleCatalog(UUID orgId) {
        if (orgId == null) {
            return List.of();
        }
        return jdbc.query("""
                SELECT c.id, c.title, c.slug, c.category, c.level, c.lessons_count
                  FROM course c
                 WHERE c.state = 'PUBLISHED' AND %s
                 ORDER BY c.created_at DESC
                """.formatted(CourseVisibilityAccess.VISIBLE_TO_ORG.formatted(":orgId")),
                new MapSqlParameterSource("orgId", orgId),
                (rs, i) -> new CatalogRow(rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getString("slug"), rs.getString("category"), rs.getString("level"),
                        (Integer) rs.getObject("lessons_count")));
    }

    /* --------------------------------------------- the org admin's Courses tab */

    /** Enrollments of one org's members, folded to (course, source). */
    public record OrgSourceAggregate(UUID courseId, EnrollmentSource source, int learners,
                                     int completed, boolean required, Instant deadline,
                                     Instant assignedAt, String assignedByName) {}

    public List<OrgSourceAggregate> orgEnrolmentAggregates(UUID orgId) {
        return jdbc.query("""
                SELECT e.course_id, e.source,
                       count(*)                                        AS learners,
                       count(*) FILTER (WHERE e.status = 'COMPLETED')  AS completed,
                       bool_or(e.required)                             AS required,
                       min(e.deadline)                                 AS deadline,
                       min(e.enrolled_at)                              AS assigned_at,
                       min(ab.name)                                    AS assigned_by_name
                  FROM enrollment e
                  JOIN users u ON u.id = e.user_id
                  LEFT JOIN users ab ON ab.id = e.assigned_by
                 WHERE u.organization_id = :orgId AND e.status <> 'CANCELLED'
                 GROUP BY e.course_id, e.source
                """,
                new MapSqlParameterSource("orgId", orgId),
                (rs, i) -> new OrgSourceAggregate(
                        rs.getObject("course_id", UUID.class),
                        EnrollmentSource.of(rs.getString("source")),
                        rs.getInt("learners"), rs.getInt("completed"),
                        rs.getBoolean("required"), instant(rs, "deadline"),
                        instant(rs, "assigned_at"), rs.getString("assigned_by_name")));
    }

    /** Course → how many of the org's members finished it (any source). Rule completion needs this. */
    public Map<UUID, Integer> orgCompletionsByCourse(UUID orgId) {
        Map<UUID, Integer> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT e.course_id, count(*) AS completed
                  FROM enrollment e JOIN users u ON u.id = e.user_id
                 WHERE u.organization_id = :orgId AND e.status = 'COMPLETED'
                 GROUP BY e.course_id
                """,
                new MapSqlParameterSource("orgId", orgId),
                rs -> { out.put(rs.getObject("course_id", UUID.class), rs.getInt("completed")); });
        return out;
    }

    /** Course → members of this org opted out of its rule. */
    public Map<UUID, Integer> orgExclusionsByCourse(UUID orgId) {
        Map<UUID, Integer> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT o.course_id, count(*) AS excluded
                  FROM enrolment_overrides o JOIN users u ON u.id = o.user_id
                 WHERE u.organization_id = :orgId
                 GROUP BY o.course_id
                """,
                new MapSqlParameterSource("orgId", orgId),
                rs -> { out.put(rs.getObject("course_id", UUID.class), rs.getInt("excluded")); });
        return out;
    }

    /** Every rule of one org, with the §7b "who assigned it" resolved. */
    public List<RuleRow> orgRules(UUID orgId) {
        return jdbc.query("""
                SELECT r.course_id, r.required, r.deadline, r.updated_at,
                       COALESCE(ub.name, cb.name) AS created_by_name
                  FROM org_course_rules r
                  LEFT JOIN users cb ON cb.id = r.created_by
                  LEFT JOIN users ub ON ub.id = r.updated_by
                 WHERE r.org_id = :orgId
                """,
                new MapSqlParameterSource("orgId", orgId),
                (rs, i) -> new RuleRow(rs.getObject("course_id", UUID.class), rs.getBoolean("required"),
                        instant(rs, "deadline"), instant(rs, "updated_at"), rs.getString("created_by_name")));
    }

    /** Course → org members holding an open AI suggestion nobody has accepted. */
    public Map<UUID, Integer> orgPendingSuggestionsByCourse(UUID orgId) {
        Map<UUID, Integer> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT a.course_id, count(DISTINCT a.user_id) AS pending
                  FROM auto_enrolments a JOIN users u ON u.id = a.user_id
                 WHERE u.organization_id = :orgId
                   AND a.outcome = 'SUGGESTED' AND a.accepted_at IS NULL
                 GROUP BY a.course_id
                """,
                new MapSqlParameterSource("orgId", orgId),
                rs -> { out.put(rs.getObject("course_id", UUID.class), rs.getInt("pending")); });
        return out;
    }

    /** Active members of the org — the denominator an org rule's completion % is over. */
    public int orgMemberCount(UUID orgId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM users
                 WHERE organization_id = :orgId AND status <> 'DEACTIVATED'
                """, new MapSqlParameterSource("orgId", orgId), Integer.class);
        return n == null ? 0 : n;
    }


    /* ---------------------------------------------- the platform's visibility screen */

    public record VisibilityRow(UUID courseId, String title, String category, Integer lessonsCount,
                                String state, String visibility, String minTier, List<UUID> orgIds,
                                Instant updatedAt, String updatedByName) {}

    public List<VisibilityRow> allCourseVisibility() {
        Map<UUID, List<UUID>> orgLists = new LinkedHashMap<>();
        jdbc.getJdbcTemplate().query(
                "SELECT course_id, org_id FROM course_visible_orgs ORDER BY created_at",
                rs -> {
                    orgLists.computeIfAbsent(rs.getObject("course_id", UUID.class), k -> new java.util.ArrayList<>())
                            .add(rs.getObject("org_id", UUID.class));
                });
        return jdbc.getJdbcTemplate().query("""
                SELECT c.id, c.title, c.category, c.lessons_count, c.state,
                       c.org_visibility, c.org_visibility_min_tier,
                       c.org_visibility_updated_at, ub.name AS updated_by_name
                  FROM course c
                  LEFT JOIN users ub ON ub.id = c.org_visibility_updated_by
                 ORDER BY c.title
                """,
                (rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new VisibilityRow(id, rs.getString("title"), rs.getString("category"),
                            (Integer) rs.getObject("lessons_count"), rs.getString("state"),
                            rs.getString("org_visibility"), rs.getString("org_visibility_min_tier"),
                            orgLists.getOrDefault(id, List.of()),
                            instant(rs, "org_visibility_updated_at"), rs.getString("updated_by_name"));
                });
    }

    /** The org-list picker's options. */
    public record OrgOption(UUID orgId, String name) {}

    public List<OrgOption> assignableOrgs() {
        return jdbc.getJdbcTemplate().query("""
                SELECT id, name FROM organizations WHERE is_active = TRUE ORDER BY name
                """, (rs, i) -> new OrgOption(rs.getObject("id", UUID.class), rs.getString("name")));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
