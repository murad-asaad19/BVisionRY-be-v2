package com.bvisionry.exercise.repository;

import com.bvisionry.exercise.dto.ExercisePlacementsResponse.CohortPlacement;
import com.bvisionry.exercise.dto.ExercisePlacementsResponse.OrgPlacement;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Where each exercise template is in use. Raw SQL, same stance as
 * {@link com.bvisionry.comparison.repository.NarrativeActivityRepository}: the
 * cohort side of the answer lives in the programflow slice and the ArchUnit
 * ratchet forbids new feature-to-feature imports, so this reads that slice's
 * SCHEMA (program_tasks/program_modules/cohorts) instead of its types.
 */
@Repository
public class ExercisePlacementRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ExercisePlacementRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * templateId → how many places hand it out: one per org with a direct
     * assignment, one per cohort task. The public link is NOT counted here —
     * the template row already carries that flag.
     */
    public Map<UUID, Integer> countByTemplate() {
        Map<UUID, Integer> counts = new HashMap<>();
        jdbc.query("""
                SELECT template_id, count(*) AS n
                FROM (
                    SELECT DISTINCT a.template_id AS template_id, a.organization_id AS place_id
                    FROM exercise_assignments a
                    WHERE a.program_task_id IS NULL
                    UNION ALL
                    SELECT t.ref_id AS template_id, t.id AS place_id
                    FROM program_tasks t
                    WHERE t.task_type = 'EXERCISE' AND t.ref_id IS NOT NULL
                ) p
                GROUP BY template_id
                """, rs -> {
            counts.put(rs.getObject("template_id", UUID.class), rs.getInt("n"));
        });
        return counts;
    }

    /**
     * Templates that can no longer be deleted: something is attached that a
     * delete would cascade away. MUST stay the batch twin of the per-template
     * guard in {@code ExerciseTemplateService.delete} — assignments of ANY
     * grain (member, provision, cohort task) and any anonymous public
     * response. The list asks this so the delete control can be disabled up
     * front instead of failing on the click.
     */
    public Set<UUID> undeletableTemplateIds() {
        return Set.copyOf(jdbc.queryForList("""
                SELECT template_id FROM exercise_assignments
                UNION
                SELECT template_id FROM public_exercise_responses
                """, Map.of(), UUID.class));
    }

    /** Orgs holding this template, each with the members assigned directly. */
    public List<OrgPlacement> organizations(UUID templateId) {
        Map<UUID, OrgPlacement> byOrg = new LinkedHashMap<>();
        jdbc.query("""
                SELECT a.organization_id, o.name AS org_name,
                       u.id AS user_id, u.name AS user_name, u.email
                FROM exercise_assignments a
                JOIN organizations o ON o.id = a.organization_id
                LEFT JOIN users u ON u.id = a.user_id
                WHERE a.template_id = :templateId AND a.program_task_id IS NULL
                ORDER BY o.name, u.name NULLS FIRST
                """, new MapSqlParameterSource("templateId", templateId), rs -> {
            UUID orgId = rs.getObject("organization_id", UUID.class);
            String orgName = rs.getString("org_name");
            OrgPlacement org = byOrg.computeIfAbsent(orgId,
                    id -> new OrgPlacement(id, orgName, new ArrayList<>()));
            UUID userId = rs.getObject("user_id", UUID.class);
            if (userId != null) {
                org.members().add(new OrgPlacement.AssignedMember(
                        userId, rs.getString("user_name"), rs.getString("email")));
            }
        });
        return List.copyOf(byOrg.values());
    }

    /** Cohort board tasks referencing this template, board order. */
    public List<CohortPlacement> cohorts(UUID templateId) {
        return jdbc.query("""
                SELECT c.id AS cohort_id, c.name AS cohort_name, m.name AS module_name,
                       t.name AS task_name, (t.status = 'LIVE') AS live
                FROM program_tasks t
                JOIN program_modules m ON m.id = t.module_id
                JOIN cohorts c ON c.id = m.cohort_id
                WHERE t.task_type = 'EXERCISE' AND t.ref_id = :templateId
                ORDER BY c.name, m.position, t.position
                """, new MapSqlParameterSource("templateId", templateId),
                (rs, i) -> new CohortPlacement(
                        rs.getObject("cohort_id", UUID.class),
                        rs.getString("cohort_name"),
                        rs.getString("module_name"),
                        rs.getString("task_name"),
                        rs.getBoolean("live")));
    }
}
