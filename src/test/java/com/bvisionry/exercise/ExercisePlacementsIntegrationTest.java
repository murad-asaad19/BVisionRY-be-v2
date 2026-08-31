package com.bvisionry.exercise;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Where is this exercise assigned?" — the three surfaces that hand a template
 * out (org console, cohort board task, public link) must all show up, and a
 * template nobody uses must report zero placements so the list can disable its
 * button.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class ExercisePlacementsIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;

    private Organization org;
    private User admin;
    private User member;
    private UUID usedTemplateId;
    private UUID unusedTemplateId;

    @BeforeEach
    void seed() {
        org = new Organization();
        org.setName("Placement Org");
        org.setActive(true);
        org = organizationRepository.saveAndFlush(org);

        admin = saveUser("placement.admin@test.invalid", UserRole.SUPER_ADMIN);
        member = saveUser("placement.member@test.invalid", UserRole.MEMBER);

        usedTemplateId = insertTemplate("Placed Grid", true);
        unusedTemplateId = insertTemplate("Shelf Grid", false);

        // Org grain: the provision plus one direct member assignment.
        insertAssignment(usedTemplateId, null, null);
        insertAssignment(usedTemplateId, member.getId(), null);
        // Cohort grain: a tagged row is NOT a placement — the task is.
        insertAssignment(usedTemplateId, member.getId(), UUID.randomUUID());

        UUID cohortId = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, name, status) VALUES (?, 'Spring Cohort', 'LAUNCHED')",
                cohortId);
        UUID moduleId = UUID.randomUUID();
        jdbc.update("INSERT INTO program_modules (id, cohort_id, name, position) VALUES (?, ?, 'Week 01', 0)",
                moduleId, cohortId);
        jdbc.update("""
                INSERT INTO program_tasks (id, module_id, name, status, task_type, ref_id)
                VALUES (?, ?, 'Fill the power leak sheet', 'LIVE', 'EXERCISE', ?)
                """, UUID.randomUUID(), moduleId, usedTemplateId);

        TestAuthentication.authenticate(admin);
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    @Test
    void placementsListEveryOrgCohortAndThePublicLink() throws Exception {
        mockMvc.perform(get("/api/admin/exercise-templates/" + usedTemplateId + "/placements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizations.length()").value(1))
                .andExpect(jsonPath("$.organizations[0].organizationName").value("Placement Org"))
                // The cohort-tagged row must not double as a direct member.
                .andExpect(jsonPath("$.organizations[0].members.length()").value(1))
                .andExpect(jsonPath("$.organizations[0].members[0].email")
                        .value("placement.member@test.invalid"))
                .andExpect(jsonPath("$.cohorts.length()").value(1))
                .andExpect(jsonPath("$.cohorts[0].cohortName").value("Spring Cohort"))
                .andExpect(jsonPath("$.cohorts[0].moduleName").value("Week 01"))
                .andExpect(jsonPath("$.cohorts[0].live").value(true))
                .andExpect(jsonPath("$.publicLink").value(true));
    }

    @Test
    void anUnusedTemplateHasNoPlacementsAtAll() throws Exception {
        mockMvc.perform(get("/api/admin/exercise-templates/" + unusedTemplateId + "/placements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizations.length()").value(0))
                .andExpect(jsonPath("$.cohorts.length()").value(0))
                .andExpect(jsonPath("$.publicLink").value(false));
    }

    /** The list count is what enables/disables the button, so it counts all three. */
    @Test
    void theListCountsOrgsCohortTasksAndThePublicLink() throws Exception {
        mockMvc.perform(get("/api/admin/exercise-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + usedTemplateId + "')].placementCount")
                        .value(3))
                .andExpect(jsonPath("$[?(@.id == '" + unusedTemplateId + "')].placementCount")
                        .value(0));
    }

    /**
     * The list's `deletable` flag and the DELETE guard are two implementations
     * of one rule (batch vs per-template). If they drift, the console offers a
     * delete the server then refuses — the exact failure this flag exists to
     * prevent.
     */
    @Test
    void theListMarksAnAssignedExerciseUndeletableAndTheServerAgrees() throws Exception {
        mockMvc.perform(get("/api/admin/exercise-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + usedTemplateId + "')].deletable")
                        .value(false))
                .andExpect(jsonPath("$[?(@.id == '" + unusedTemplateId + "')].deletable")
                        .value(true));

        mockMvc.perform(delete("/api/admin/exercise-templates/" + usedTemplateId))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/admin/exercise-templates/" + unusedTemplateId))
                .andExpect(status().isNoContent());
    }

    private UUID insertTemplate(String name, boolean isPublic) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_templates (id, name, status, created_by, is_public, public_token)
                VALUES (?, ?, 'PUBLISHED', ?, ?, ?)
                """, id, name, admin.getId(), isPublic, isPublic ? UUID.randomUUID() : null);
        return id;
    }

    private void insertAssignment(UUID templateId, UUID userId, UUID programTaskId) {
        jdbc.update("""
                INSERT INTO exercise_assignments
                    (id, template_id, organization_id, user_id, assigned_by, program_task_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), templateId, org.getId(), userId, admin.getId(), programTaskId);
    }

    private User saveUser(String email, UserRole role) {
        User user = new User();
        user.setEmail(email);
        user.setName(email.substring(0, email.indexOf('@')));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setOrganization(org);
        return userRepository.saveAndFlush(user);
    }
}
