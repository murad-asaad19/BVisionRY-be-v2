package com.bvisionry.common.coursevisibility;

import com.bvisionry.common.enums.SubscriptionTier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The single SQL source of truth for "may this organization see this course?"
 * (redesign spec §3 visibility).
 *
 * <p>Five surfaces ask the question — the org admin's Courses tab, the member's
 * Library, the AI suggestion engine, the cohort board's COURSE task picker and
 * the course player — and if their predicates diverge an org gets recommended a
 * course it cannot open, which is the one outcome the spec names explicitly.
 * Like {@link com.bvisionry.common.programaccess.TaskCompletion} it lives in
 * {@code common} because the ArchUnit ratchet forbids feature→feature imports:
 * it depends on the SCHEMA only and imports no catalog type.
 *
 * <p><strong>Tier comparison.</strong> {@link SubscriptionTier}'s declaration
 * order IS the capacity order, so the SQL ranks tiers with an
 * {@code array_position} over an array generated from the enum below — the
 * ranking has one home, not two. An unknown or NULL tier ranks NULL, so the
 * comparison is false and the course stays hidden: this fails CLOSED.
 *
 * <p><strong>Sub-orgs inherit the plan</strong> (spec §8), so MIN_TIER resolves
 * the tier at the billing root — an org's parent when it has one, itself
 * otherwise.
 */
@Component
public class CourseVisibilityAccess {

    /** {@code ARRAY['FREE','STARTER',...]} in capacity order, from the enum. */
    private static final String TIER_RANKING = Arrays.stream(SubscriptionTier.values())
            .map(t -> "'" + t.name() + "'")
            .collect(Collectors.joining(",", "ARRAY[", "]"));

    /** The billing-root tier of the org given by {@code %1$s}. */
    private static final String BILLING_TIER = """
            (SELECT COALESCE(dop.subscription_tier, dorg.subscription_tier)
               FROM organizations dorg
               LEFT JOIN organizations dop ON dop.id = dorg.parent_organization_id
              WHERE dorg.id = %1$s)""";

    /**
     * Boolean SQL expression: the org given by {@code %1$s} (a column or bind
     * parameter supplied by the composing query) may see the course the
     * composing query exposes as {@code c}. Inner aliases are prefixed
     * {@code d*} so they never shadow a composing query's own.
     */
    public static final String VISIBLE_TO_ORG =
            "(c.org_visibility = 'EVERYONE'\n"
            + " OR (c.org_visibility = 'ORG_LIST' AND EXISTS (\n"
            + "        SELECT 1 FROM course_visible_orgs dcvo\n"
            + "         WHERE dcvo.course_id = c.id AND dcvo.org_id = %1$s))\n"
            + " OR (c.org_visibility = 'MIN_TIER'\n"
            + "     AND array_position(" + TIER_RANKING + ", " + BILLING_TIER + ")\n"
            + "         >= array_position(" + TIER_RANKING + ", c.org_visibility_min_tier)))";

    private final NamedParameterJdbcTemplate jdbc;

    public CourseVisibilityAccess(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every PUBLISHED course this org may see and assign from. */
    public Set<UUID> visibleCourseIds(UUID orgId) {
        if (orgId == null) {
            return Set.of();
        }
        return new HashSet<>(jdbc.query(
                "SELECT c.id FROM course c WHERE c.state = 'PUBLISHED' AND "
                        + VISIBLE_TO_ORG.formatted(":orgId"),
                new MapSqlParameterSource("orgId", orgId),
                (rs, i) -> rs.getObject("id", UUID.class)));
    }

    /** The subset of {@code courseIds} this org may see, whatever the course state. */
    public Set<UUID> filterVisible(UUID orgId, Collection<UUID> courseIds) {
        if (orgId == null || courseIds == null || courseIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.query(
                "SELECT c.id FROM course c WHERE c.id IN (:ids) AND "
                        + VISIBLE_TO_ORG.formatted(":orgId"),
                new MapSqlParameterSource("orgId", orgId).addValue("ids", List.copyOf(courseIds)),
                (rs, i) -> rs.getObject("id", UUID.class)));
    }

    /**
     * The subset of {@code courseIds} this USER's organization may see — the AI
     * engine's batch guard, where the org is not in hand and one query per
     * candidate would be silly. An org-less user is not gated (see
     * {@link #isVisibleToUser}).
     */
    public Set<UUID> filterVisibleForUser(UUID userId, Collection<UUID> courseIds) {
        if (userId == null || courseIds == null || courseIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.query(
                "SELECT c.id FROM course c, users u WHERE u.id = :userId AND c.id IN (:ids)"
                        + " AND (u.organization_id IS NULL OR "
                        + VISIBLE_TO_ORG.formatted("u.organization_id") + ")",
                new MapSqlParameterSource("userId", userId).addValue("ids", List.copyOf(courseIds)),
                (rs, i) -> rs.getObject("id", UUID.class)));
    }

    /** One course, one org. Says nothing about the course's STATE. */
    public boolean isVisibleToOrg(UUID courseId, UUID orgId) {
        return orgId != null && courseId != null && !filterVisible(orgId, List.of(courseId)).isEmpty();
    }

    /**
     * The predicate every ACT that creates a seat must use: PUBLISHED <em>and</em>
     * visible to this org.
     *
     * <p>Visibility alone is not enough. A DRAFT or ARCHIVED course is not
     * something an org admin may assign or a member may take up — the
     * auto-enrolment engine has always refused one
     * ({@code AutoEnrolmentOutcome.COURSE_NOT_PUBLISHED}) and Suggest mode
     * deliberately defers that check to here, so this is where it has to happen.
     * One method, so assign and accept cannot drift apart.
     */
    public boolean isAssignable(UUID courseId, UUID orgId) {
        if (orgId == null || courseId == null) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM course c WHERE c.id = :courseId"
                        + " AND c.state = 'PUBLISHED' AND "
                        + VISIBLE_TO_ORG.formatted(":orgId") + ")",
                new MapSqlParameterSource("courseId", courseId).addValue("orgId", orgId),
                Boolean.class));
    }

    /** The complement of {@link #filterVisible} — what this org may NOT see. */
    public Set<UUID> invisibleCourseIds(UUID orgId, Collection<UUID> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> visible = filterVisible(orgId, courseIds);
        Set<UUID> blocked = new HashSet<>(courseIds);
        blocked.removeAll(visible);
        return blocked;
    }

    /**
     * One course, one USER — the player's guard, where the org is not in hand.
     *
     * <p>A user with no organization (super admin, an unattached account) is
     * NOT gated: visibility exists to curate what an org's people are offered,
     * and a platform operator must be able to open anything they author.
     */
    public boolean isVisibleToUser(UUID userId, UUID courseId) {
        // query(), not queryForObject(): a missing user or course yields no row,
        // and that must be a plain false rather than an exception thrown from a
        // guard whose only job is to answer yes/no.
        return jdbc.query(
                "SELECT (u.organization_id IS NULL OR "
                        + VISIBLE_TO_ORG.formatted("u.organization_id")
                        + ") AS visible FROM users u, course c WHERE u.id = :userId AND c.id = :courseId",
                new MapSqlParameterSource("userId", userId).addValue("courseId", courseId),
                (rs, i) -> rs.getBoolean("visible"))
                .stream().findFirst().orElse(false);
    }
}
