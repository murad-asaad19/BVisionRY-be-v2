package com.bvisionry.common.programaccess;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The org-scoped cohort lookup every org surface anchors on: a cohort "exists"
 * for an org only when {@code cohort_orgs} assigns it (spec §13). Empty means
 * absent-or-foreign — callers 404 without distinguishing the two. Was four
 * byte-identical copies across coaching, communication, comparison and
 * insights; lives in {@code common} because the ArchUnit ratchet forbids
 * feature→feature imports and this depends on the schema only.
 */
@Component
public class OrgCohortAccess {

    private final NamedParameterJdbcTemplate jdbc;

    public OrgCohortAccess(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The cohort's name, constrained to the org — empty when absent or foreign. */
    public Optional<String> cohortNameInOrg(UUID orgId, UUID cohortId) {
        return jdbc.query("""
                SELECT c.name FROM cohorts c
                JOIN cohort_orgs cox ON cox.cohort_id = c.id AND cox.org_id = :orgId
                WHERE c.id = :cohortId
                """,
                new MapSqlParameterSource("orgId", orgId).addValue("cohortId", cohortId),
                (rs, i) -> rs.getString("name"))
                .stream().findFirst();
    }
}
