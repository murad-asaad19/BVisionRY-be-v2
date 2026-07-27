package com.bvisionry.pipeline.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * "Which courses has an admin said this founder must not be auto-enrolled in?"
 *
 * <p>Read-only, and the pipeline slice's whole view of {@code enrolment_overrides}
 * (V157). The WRITE side lives in the organization slice, behind the ORG_ADMIN
 * tenancy gate that proves the founder belongs to the caller's org — a decision
 * this slice has no principal to make, because the engine runs on the
 * evaluation's async thread where there is no authenticated user at all.
 *
 * <p><strong>Why raw SQL rather than a shared entity.</strong> Same reason as
 * {@link FounderEnrolmentWriteRepository} and {@link CourseCatalogReadRepository}
 * next to it: the ArchUnit ratchet ({@code noCrossFeatureDependencies}) forbids
 * new feature -&gt; feature edges and the frozen-violations store is never
 * written, so both sides of this table depend on the SCHEMA and neither imports
 * the other's types.
 */
@Repository
public class EnrolmentOverrideReadRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public EnrolmentOverrideReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every course this founder has been removed from by an admin.
     *
     * <p><strong>Not filtered by submission, and that is the whole design.</strong>
     * The auto-enrolment ledger is keyed per evaluation, so a decision recorded
     * against one submission cannot speak for the next one; this table is keyed
     * {@code (user_id, course_id)} precisely so it can. A founder re-assessed a
     * year later still hits this set.
     *
     * <p>ponytail: one index-only read per evaluation on
     * {@code uq_enrolment_overrides}, no cache. An override set is a handful of
     * rows; revisit only if a profile ever says otherwise.
     */
    public Set<UUID> findCourseIdsFor(UUID userId) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT course_id FROM enrolment_overrides WHERE user_id = :userId",
                new MapSqlParameterSource("userId", userId), UUID.class));
    }
}
