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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The V173 grain split on {@code exercise_assignments}: untagged rows
 * ({@code program_task_id IS NULL}) belong to the org exercise console;
 * tagged rows are spawned by cohort tasks and live on the cohort board.
 * The admin surface must neither list, block on, nor delete tagged rows.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class ExerciseCohortTaskAssignmentIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private jakarta.persistence.EntityManager em;

    private Organization org;
    private User admin;
    private User member;
    private UUID templateId;
    private UUID provisionId;
    private UUID taggedId;

    @BeforeEach
    void seed() {
        org = new Organization();
        org.setName("Cohort Task Grain Org");
        org.setActive(true);
        org = organizationRepository.saveAndFlush(org);

        admin = saveUser("ctg.admin@test.invalid", UserRole.ORG_ADMIN);
        member = saveUser("ctg.member@test.invalid", UserRole.MEMBER);

        templateId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_templates (id, name, status, created_by)
                VALUES (?, 'Grain Grid', 'PUBLISHED', ?)
                """, templateId, admin.getId());
        provisionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_assignments (id, template_id, organization_id, user_id, assigned_by)
                VALUES (?, ?, ?, NULL, ?)
                """, provisionId, templateId, org.getId(), admin.getId());
        // A cohort-task-spawned row — in prod written only by
        // TaskSpineRepository.ensureExerciseSubmission's raw SQL.
        taggedId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_assignments
                    (id, template_id, organization_id, user_id, assigned_by, program_task_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, taggedId, templateId, org.getId(), member.getId(), admin.getId(),
                UUID.randomUUID());
        TestAuthentication.authenticate(admin);
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    @Test
    void theAdminListShowsOnlyDirectRows() throws Exception {
        mockMvc.perform(get(listUrl()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", not(hasItem(taggedId.toString()))))
                .andExpect(jsonPath("$[*].id", hasItem(provisionId.toString())));
    }

    @Test
    void aCohortTaskRowDoesNotBlockADirectAssignment() throws Exception {
        mockMvc.perform(post(listUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"" + templateId + "\","
                                + "\"memberIds\":[\"" + member.getId() + "\"]}"))
                .andExpect(status().is2xxSuccessful());
        // One transaction per test: flush the JPA write so raw SQL sees it.
        em.flush();

        // Two rows for (org, template, member): the tagged one and the fresh
        // direct one — the V173 grains are independent.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM exercise_assignments
                WHERE organization_id = ? AND template_id = ? AND user_id = ?
                """, Integer.class, org.getId(), templateId, member.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM exercise_assignments
                WHERE organization_id = ? AND template_id = ? AND user_id = ?
                  AND program_task_id IS NULL
                """, Integer.class, org.getId(), templateId, member.getId())).isEqualTo(1);
    }

    @Test
    void aCohortTaskRowCannotBeCancelledFromTheOrgConsole() throws Exception {
        mockMvc.perform(delete(listUrl() + "/" + taggedId))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM exercise_assignments WHERE id = ?",
                Integer.class, taggedId)).isEqualTo(1);
    }

    private String listUrl() {
        return "/api/organizations/" + org.getId() + "/exercise-assignments";
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
