package com.bvisionry.programflow.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bvisionry.programflow.domain.Cohort;

public interface CohortRepository extends JpaRepository<Cohort, UUID> {

    /** Every platform cohort in board order (super-admin authoring console). */
    List<Cohort> findAllByOrderByPositionAsc();

    /** The cohorts assigned to an org, in board order (org participation view). */
    @Query(value = """
            SELECT c.* FROM cohorts c
            JOIN cohort_orgs co ON co.cohort_id = c.id
            WHERE co.org_id = :orgId
            ORDER BY c.position
            """, nativeQuery = true)
    List<Cohort> findAssigned(@Param("orgId") UUID orgId);

    /**
     * Module count + stage label per cohort assigned to an org (spec §8
     * cohort-card progress bar). {@code LEFT JOIN program_settings} because
     * that row is optional — its absence means the "Week" default, same as
     * {@link com.bvisionry.programflow.domain.ProgramSettings}.
     *
     * The count is of PACED modules only: the card reads "Week 3/8", so
     * always-on material (orientation, a closing letter) would otherwise
     * lengthen a schedule it is not part of.
     */
    @Query(value = """
            SELECT c.id AS cohortId,
                   (SELECT count(*) FROM program_modules pm
                     WHERE pm.cohort_id = c.id AND pm.paced) AS moduleCount,
                   COALESCE(ps.stage_label, 'Week') AS stageLabel
            FROM cohorts c
            JOIN cohort_orgs co ON co.cohort_id = c.id
            LEFT JOIN program_settings ps ON ps.cohort_id = c.id
            WHERE co.org_id = :orgId
            """, nativeQuery = true)
    List<CohortProgressRow> findAssignedProgressStats(@Param("orgId") UUID orgId);

    /** Active members (role MEMBER) of an org — enrollment pickers and roster validation. */
    @Query(value = """
            SELECT u.id AS id, u.name AS name, u.email AS email
            FROM users u
            WHERE u.organization_id = :orgId
              AND u.status = 'ACTIVE'
              AND u.role = 'MEMBER'
            ORDER BY u.name
            """, nativeQuery = true)
    List<OrgMemberRow> findOrgMembers(@Param("orgId") UUID orgId);

    /**
     * Whether the user is an enrollable member of the org — the single-id form of
     * {@link #findOrgMembers}'s predicate (active MEMBER), so the auto-enroll hook
     * can test one id without materialising the whole roster. Keep this WHERE in
     * sync with {@code findOrgMembers}.
     */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM users u
                           WHERE u.id = :userId AND u.organization_id = :orgId
                             AND u.status = 'ACTIVE' AND u.role = 'MEMBER')
            """, nativeQuery = true)
    boolean isActiveOrgMember(@Param("orgId") UUID orgId, @Param("userId") UUID userId);

    /**
     * The cohort's roster with each founder's org: active enrolled members,
     * the single source every cohort-board count reads (board stats, module
     * "reached", pulse, matrix). Spec §13: a roster may span orgs.
     */
    @Query(value = """
            SELECT u.id AS id, u.name AS name, u.email AS email,
                   u.organization_id AS orgId, o.name AS orgName
            FROM cohort_members cm
            JOIN users u ON u.id = cm.user_id
            JOIN organizations o ON o.id = u.organization_id
            WHERE cm.cohort_id = :cohortId
              AND u.status = 'ACTIVE'
              AND u.role = 'MEMBER'
            ORDER BY u.name
            """, nativeQuery = true)
    List<CohortMemberRow> findRoster(@Param("cohortId") UUID cohortId);

    /**
     * The cohorts a learner is enrolled in AND may see (spec §8: members see
     * LAUNCHED + COMPLETED; DRAFT and ARCHIVED are invisible) — LAUNCHED
     * first, then by position. Every member-facing read routes through this,
     * so the visibility rule cannot fork.
     */
    @Query("""
            SELECT c FROM Cohort c
            JOIN c.memberIds m
            WHERE m = :userId
              AND c.status IN (com.bvisionry.programflow.domain.CohortStatus.LAUNCHED,
                               com.bvisionry.programflow.domain.CohortStatus.COMPLETED)
            ORDER BY CASE WHEN c.status = com.bvisionry.programflow.domain.CohortStatus.LAUNCHED
                          THEN 0 ELSE 1 END, c.position ASC
            """)
    List<Cohort> findEnrolled(@Param("userId") UUID userId);

    /**
     * Every sub-organization with its parent's name, console membership and
     * active-learner / cohort / workshop counts. Only sub-orgs are program or
     * workshop surfaces — the inner join on the parent filters root orgs out.
     * Each switcher lists the orgs on its surface, each "add organization"
     * picker offers the rest. Soft-coupled by SQL like TeamRepository.
     */
    @Query(value = """
            SELECT o.id AS id, o.name AS name, o.description AS description,
                   p.name AS parentName,
                   (SELECT count(*) FROM users u WHERE u.organization_id = o.id
                     AND u.role = 'MEMBER' AND u.status = 'ACTIVE') AS memberCount,
                   (SELECT count(*) FROM cohort_orgs co WHERE co.org_id = o.id) AS cohortCount,
                   (SELECT count(*) FROM workshops w WHERE w.org_id = o.id) AS workshopCount,
                   EXISTS (SELECT 1 FROM program_surface_orgs s
                            WHERE s.org_id = o.id AND s.surface = 'PROGRAM_FLOW') AS inProgramFlow,
                   EXISTS (SELECT 1 FROM program_surface_orgs s
                            WHERE s.org_id = o.id AND s.surface = 'WORKSHOPS') AS inWorkshops
            FROM organizations o
            JOIN organizations p ON p.id = o.parent_organization_id
            ORDER BY p.name, o.name
            """, nativeQuery = true)
    List<OrgProgramRow> findOrgProgramRows();

    /**
     * Every cohort→org assignment as (cohortId, org display name), one row per
     * pair, so the platform switcher can disambiguate two same-named cohorts
     * in a single read (spec §13 — names are no longer unique per org).
     */
    @Query(value = """
            SELECT co.cohort_id AS cohortId,
                   CASE WHEN p.name IS NULL THEN o.name ELSE p.name || ' → ' || o.name END AS orgName
            FROM cohort_orgs co
            JOIN organizations o ON o.id = co.org_id
            LEFT JOIN organizations p ON p.id = o.parent_organization_id
            ORDER BY p.name NULLS FIRST, o.name
            """, nativeQuery = true)
    List<CohortOrgNameRow> findAllOrgNames();

    /** Soft-coupled org existence check (programflow may not import the organization feature). */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM organizations WHERE id = :orgId)", nativeQuery = true)
    boolean orgExists(@Param("orgId") UUID orgId);

    /**
     * Atomically bump the board version IFF it still equals {@code expectedVersion};
     * returns the row count (1 = won, 0 = someone else saved first). board_version
     * is a plain column, not {@code @Version}, so a read-then-write guard is not
     * atomic — two concurrent saves could both pass a value check and both clobber.
     * This conditional UPDATE serializes them on the row. {@code clearAutomatically}
     * detaches the now-stale managed cohort so it cannot overwrite the bump on flush.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE cohorts SET board_version = board_version + 1
            WHERE id = :cohortId AND board_version = :expectedVersion
            """, nativeQuery = true)
    int bumpBoardVersionIfMatches(@Param("cohortId") UUID cohortId,
                                  @Param("expectedVersion") long expectedVersion);

    /** The cohort's org assignments with names and per-org headcounts (assign panel). */
    @Query(value = """
            SELECT co.org_id AS orgId, o.name AS orgName, p.name AS parentName,
                   co.auto_enroll AS autoEnroll,
                   co.assigned_at AS assignedAt,
                   (SELECT count(*) FROM cohort_members cm
                     JOIN users u ON u.id = cm.user_id
                    WHERE cm.cohort_id = co.cohort_id
                      AND u.organization_id = co.org_id) AS enrolledCount
            FROM cohort_orgs co
            JOIN organizations o ON o.id = co.org_id
            LEFT JOIN organizations p ON p.id = o.parent_organization_id
            WHERE co.cohort_id = :cohortId
            ORDER BY p.name NULLS FIRST, o.name
            """, nativeQuery = true)
    List<CohortOrgRow> findAssignmentRows(@Param("cohortId") UUID cohortId);

    /** Lists the org on a console. Idempotent — re-adding an already-listed org is a no-op. */
    @Modifying
    @Query(value = """
            INSERT INTO program_surface_orgs (org_id, surface) VALUES (:orgId, :surface)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    void addToSurface(@Param("orgId") UUID orgId, @Param("surface") String surface);

    /** Takes the org off a console. Its cohorts / workshops are untouched. */
    @Modifying
    @Query(value = "DELETE FROM program_surface_orgs WHERE org_id = :orgId AND surface = :surface",
            nativeQuery = true)
    void removeFromSurface(@Param("orgId") UUID orgId, @Param("surface") String surface);
}
