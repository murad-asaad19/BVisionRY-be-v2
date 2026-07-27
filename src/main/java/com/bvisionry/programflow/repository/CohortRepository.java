package com.bvisionry.programflow.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bvisionry.programflow.domain.Cohort;

public interface CohortRepository extends JpaRepository<Cohort, UUID> {

    /** All cohorts of an org, in board order (admin cohort switcher). */
    List<Cohort> findByOrgIdOrderByPositionAsc(UUID orgId);

    /** The cohorts a learner is enrolled in — ACTIVE first, then by position. */
    @Query("""
            SELECT c FROM Cohort c
            JOIN c.memberIds m
            WHERE m = :userId
            ORDER BY c.status ASC, c.position ASC
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
                   (SELECT count(*) FROM cohorts c WHERE c.org_id = o.id) AS cohortCount,
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
     * Cohorts created since {@code since} anywhere in {@code orgId}'s BILLING
     * FAMILY — the root org plus every sub-org under it.
     *
     * <p>Family-wide and not per-org on purpose. Cohorts live on sub-orgs (see
     * {@link #findOrgProgramRows()}: only sub-orgs are program surfaces) while
     * the plan is bought by the root org and merely inherited downwards, so a
     * per-org count would let a customer reset a "1 cohort per quarter" ceiling
     * by creating a new sub-organization. {@code COALESCE(parent_organization_id,
     * id)} is the billing root of any row (the hierarchy is one level deep), so
     * two orgs are in the same family iff those coincide.
     *
     * <p>Native + soft-coupled by SQL, like {@link #findOrgProgramRows()} above:
     * reading {@code organizations} from here costs no Java dependency edge on
     * the organization feature, which the ArchUnit ratchet would otherwise flag.
     */
    @Query(value = """
            SELECT count(*) FROM cohorts c
            WHERE c.created_at >= :since
              AND c.org_id IN (
                  SELECT fam.id FROM organizations fam, organizations self
                  WHERE self.id = :orgId
                    AND COALESCE(fam.parent_organization_id, fam.id)
                      = COALESCE(self.parent_organization_id, self.id)
              )
            """, nativeQuery = true)
    long countCreatedInBillingFamilySince(@Param("orgId") UUID orgId,
                                          @Param("since") OffsetDateTime since);

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
