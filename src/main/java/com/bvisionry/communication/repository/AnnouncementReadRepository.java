package com.bvisionry.communication.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.bvisionry.common.coachaccess.CoachAccess;

/**
 * Cross-feature reads for announcements, expressed as raw SQL through
 * {@link NamedParameterJdbcTemplate}.
 *
 * <p><strong>Why raw SQL rather than the owning features' repositories.</strong>
 * Announcements read cohorts and their enrolment ({@code programflow}), user
 * identity ({@code auth}) and coach grants ({@code coaching}); the ArchUnit
 * ratchet forbids new feature→feature imports, so — following
 * {@code CoachingReadRepository} and {@code common.gdpr.PersonalDataRepository}
 * — this class depends on the schema and imports no other feature's types.
 *
 * <p><strong>Every query carries the tenant in the SQL itself.</strong> A cohort
 * is only ever resolved WITH its org, so a foreign cohort id — even a real one
 * — reads as absent rather than reachable. Coach scoping composes the shared
 * {@link CoachAccess#GRANTED_COHORT_PREDICATE} instead of restating it, so the
 * "may this coach act on this cohort?" rule cannot fork.
 */
@Repository
public class AnnouncementReadRepository {

    /**
     * Hard ceiling on an author's cohort feed — NOT a page size: there is no
     * paging, so post 51 pushes post 1 out of the API's reach entirely. The
     * web feed says so when it hits the ceiling.
     * ponytail: a cohort gets a handful of broadcasts per programme; add
     * keyset paging (created_at, id) the day one runs past 50.
     */
    private static final int FEED_CEILING = 50;

    private final NamedParameterJdbcTemplate jdbc;

    public AnnouncementReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record CohortRow(UUID id, String name) {}

    public record FeedRow(UUID id, UUID cohortId, String cohortName, String authorName,
                          String body, boolean flagged, Instant createdAt) {}

    /** The cohort's name, constrained to the org — empty when absent or foreign. */
    public Optional<String> cohortNameInOrg(UUID orgId, UUID cohortId) {
        return jdbc.query("SELECT name FROM cohorts WHERE id = :cohortId AND org_id = :orgId",
                        new MapSqlParameterSource("orgId", orgId).addValue("cohortId", cohortId),
                        (rs, i) -> rs.getString("name"))
                .stream().findFirst();
    }

    /** Every cohort in the org — the org-admin's broadcast targets. */
    public List<CohortRow> cohortsInOrg(UUID orgId) {
        return jdbc.query("""
                        SELECT id, name FROM cohorts WHERE org_id = :orgId
                        ORDER BY position, name
                        """,
                new MapSqlParameterSource("orgId", orgId),
                (rs, i) -> new CohortRow(rs.getObject("id", UUID.class), rs.getString("name")));
    }

    /** The cohorts a coach holds a whole-cohort grant on — their broadcast targets. */
    public List<CohortRow> cohortsGrantedToCoach(UUID orgId, UUID coachId) {
        return jdbc.query("""
                        SELECT c.id, c.name FROM cohorts c
                        WHERE c.org_id = :orgId AND %s
                        ORDER BY c.position, c.name
                        """.formatted(CoachAccess.GRANTED_COHORT_PREDICATE.formatted("c.id")),
                new MapSqlParameterSource("orgId", orgId).addValue("coachId", coachId),
                (rs, i) -> new CohortRow(rs.getObject("id", UUID.class), rs.getString("name")));
    }

    /**
     * The recipients of a broadcast: ACTIVE users of {@code orgId} enrolled in
     * the cohort at send time, minus the author — and only while the cohort is
     * member-visible (V167: LAUNCHED/COMPLETED; a DRAFT's broadcast reaches
     * nobody until launch, an ARCHIVED one is history). Enrolment is the
     * definition of the audience, so no role filter — but the org equality is carried on the
     * user row too, so a member moved to another tenant with a stale enrolment
     * row drops out.
     */
    public List<UUID> recipientIds(UUID orgId, UUID cohortId, UUID authorId) {
        return jdbc.query("""
                        SELECT u.id
                        FROM cohort_members cm
                        JOIN users u ON u.id = cm.user_id
                                    AND u.organization_id = :orgId
                                    AND u.status = 'ACTIVE'
                        JOIN cohorts c ON c.id = cm.cohort_id AND c.org_id = :orgId
                                      AND c.status IN ('LAUNCHED', 'COMPLETED')
                        WHERE cm.cohort_id = :cohortId
                          AND u.id <> :authorId
                        ORDER BY u.id
                        """,
                new MapSqlParameterSource("orgId", orgId)
                        .addValue("cohortId", cohortId)
                        .addValue("authorId", authorId),
                (rs, i) -> rs.getObject("id", UUID.class));
    }

    /**
     * True when the user is enrolled in that org's cohort — the reporter's
     * standing. Deliberately the SAME predicate {@link #recipientIds} uses,
     * {@code u.status = 'ACTIVE'} included: standing to report is standing to
     * have received, and a suspended member holding a live token is exactly the
     * caller that would otherwise slip between the two.
     */
    public boolean isCohortMember(UUID orgId, UUID cohortId, UUID userId) {
        Boolean member = jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM cohort_members cm
                            JOIN users u   ON u.id = cm.user_id
                                          AND u.organization_id = :orgId
                                          AND u.status = 'ACTIVE'
                            JOIN cohorts c ON c.id = cm.cohort_id AND c.org_id = :orgId
                                          AND c.status IN ('LAUNCHED', 'COMPLETED')
                            WHERE cm.cohort_id = :cohortId AND cm.user_id = :userId)
                        """,
                new MapSqlParameterSource("orgId", orgId)
                        .addValue("cohortId", cohortId)
                        .addValue("userId", userId),
                Boolean.class);
        return Boolean.TRUE.equals(member);
    }

    public record MemberFeedRow(UUID id, String cohortName, String authorName, String authorRole,
                                String body, Instant createdAt) {}

    /**
     * The announcements a MEMBER received: posts to cohorts they belong to,
     * newest first, with the author's name + role so the reader can label who
     * is speaking ("Coach" / "Program admin"). No {@code flagged} — moderation
     * state is never a recipient's signal. Same ceiling as the author feed.
     */
    public List<MemberFeedRow> memberFeed(UUID orgId, UUID memberId) {
        return jdbc.query("""
                        SELECT a.id, c.name AS cohort_name, u.name AS author_name,
                               u.role AS author_role, a.body, a.created_at
                        FROM announcements a
                        JOIN cohorts c ON c.id = a.cohort_id AND c.org_id = :orgId
                                      AND c.status IN ('LAUNCHED', 'COMPLETED')
                        JOIN cohort_members cm ON cm.cohort_id = a.cohort_id
                                              AND cm.user_id = :memberId
                        LEFT JOIN users u ON u.id = a.author_id
                        WHERE a.org_id = :orgId
                        ORDER BY a.created_at DESC
                        LIMIT %d
                        """.formatted(FEED_CEILING),
                new MapSqlParameterSource("orgId", orgId).addValue("memberId", memberId),
                (rs, i) -> new MemberFeedRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("cohort_name"),
                        rs.getString("author_name"),
                        rs.getString("author_role"),
                        rs.getString("body"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()));
    }

    /** A cohort's posts, newest first, org-scoped. */
    public List<FeedRow> feed(UUID orgId, UUID cohortId) {
        return jdbc.query("""
                        SELECT a.id, a.cohort_id, c.name AS cohort_name, u.name AS author_name,
                               a.body, a.flagged_at, a.created_at
                        FROM announcements a
                        JOIN cohorts c     ON c.id = a.cohort_id AND c.org_id = :orgId
                        LEFT JOIN users u  ON u.id = a.author_id
                        WHERE a.org_id = :orgId AND a.cohort_id = :cohortId
                        ORDER BY a.created_at DESC
                        LIMIT %d
                        """.formatted(FEED_CEILING),
                new MapSqlParameterSource("orgId", orgId).addValue("cohortId", cohortId),
                (rs, i) -> new FeedRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("cohort_id", UUID.class),
                        rs.getString("cohort_name"),
                        rs.getString("author_name"),
                        rs.getString("body"),
                        rs.getObject("flagged_at") != null,
                        // pgjdbc maps timestamptz to OffsetDateTime, not Instant.
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()));
    }
}
