package com.bvisionry.workshops;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import com.bvisionry.workshops.domain.Workshop;
import com.bvisionry.workshops.domain.WorkshopExercise;
import com.bvisionry.workshops.domain.WorkshopExerciseTask;
import com.bvisionry.workshops.domain.WorkshopStatus;
import com.bvisionry.workshops.domain.WorkshopTaskAssignee;
import com.bvisionry.workshops.domain.WorkshopTaskType;
import com.bvisionry.workshops.repository.WorkshopExerciseRepository;
import com.bvisionry.workshops.repository.WorkshopExerciseTaskRepository;
import com.bvisionry.workshops.repository.WorkshopRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the workshop admin ORG-SCOPING of the ids, independent of who calls:
 * a workshop/exercise/task from another org can never be read or mutated
 * through an org path it does not belong to, whichever way the ids are
 * smuggled.
 *
 * <p>The caller is a SUPER_ADMIN because the console is SUPER_ADMIN-only
 * (see {@code WorkshopAdminController}) — which makes these cases stronger,
 * not weaker: the most privileged caller there is still cannot cross the
 * workshop→org binding. That ORG_ADMIN is refused outright is pinned in
 * {@code EndpointAuthorizationMatrixIntegrationTest.WorkshopConsole}.
 *
 * <ul>
 *   <li>Foreign workshop id under another org's path → 404
 *       ({@code requireWorkshop} treats an org mismatch as not-found so the
 *       foreign workshop's existence never leaks).</li>
 *   <li>Foreign exercise/task ids nested under another workshop → 404
 *       (parent-binding checks in {@code requireExercise}/
 *       {@code requireTask}).</li>
 *   <li>{@code dealCards} with a task whose exercise belongs to a different
 *       workshop → 404, even inside the same org.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional // each test rolls back, so fixture emails/names can't collide across methods
@EnabledIfDockerAvailable
class WorkshopAdminOrgIsolationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private WorkshopRepository workshops;
    @Autowired private WorkshopExerciseRepository exercises;
    @Autowired private WorkshopExerciseTaskRepository tasks;

    private Organization orgA;
    private Organization orgB;
    private Workshop workshopA;      // DRAFT, owned by org A
    private WorkshopExercise exerciseA;
    private Workshop workshopB;      // DRAFT, owned by org B
    private WorkshopExercise exerciseB;
    private WorkshopExerciseTask taskB;

    @BeforeEach
    void setUp() {
        orgA = newOrg("Org A");
        orgB = newOrg("Org B");
        workshopA = newWorkshop(orgA, "Workshop A");
        exerciseA = newExercise(workshopA);
        workshopB = newWorkshop(orgB, "Workshop B");
        exerciseB = newExercise(workshopB);
        taskB = newSortTask(exerciseB);

        TestAuthentication.authenticateAsSuperAdmin(userRepository);
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    @Test
    void foreignWorkshop_underAnotherOrgsPath_readsAndMutationsAre404() throws Exception {
        // Positive control: the workshop under its OWN org path is reachable.
        mockMvc.perform(get("/api/organizations/{orgId}/workshops/{id}/builder",
                        orgA.getId(), workshopA.getId()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/organizations/{orgId}/workshops/{id}/builder",
                        orgA.getId(), workshopB.getId()))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/organizations/{orgId}/workshops/{id}", orgA.getId(), workshopB.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hijacked\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/organizations/{orgId}/workshops/{id}",
                        orgA.getId(), workshopB.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void foreignExercise_smuggledUnderAnotherWorkshop_is404() throws Exception {
        mockMvc.perform(put("/api/organizations/{orgId}/workshops/{wId}/exercises/{eId}",
                        orgA.getId(), workshopA.getId(), exerciseB.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Hijacked\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/organizations/{orgId}/workshops/{wId}/exercises/{eId}",
                        orgA.getId(), workshopA.getId(), exerciseB.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void foreignTask_smuggledUnderAnotherExercise_is404() throws Exception {
        mockMvc.perform(delete("/api/organizations/{orgId}/workshops/{wId}/exercises/{eId}/tasks/{tId}",
                        orgA.getId(), workshopA.getId(), exerciseA.getId(), taskB.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void dealCards_taskFromAnotherWorkshop_is404_evenInSameOrg() throws Exception {
        Workshop otherA = newWorkshop(orgA, "Workshop A2");
        WorkshopExerciseTask sortA = newSortTask(exerciseA);

        // Positive control: dealing via the task's own workshop works.
        mockMvc.perform(post("/api/organizations/{orgId}/workshops/{wId}/assignments/{tId}/deal",
                        orgA.getId(), workshopA.getId(), sortA.getId()))
                .andExpect(status().isOk());

        // Same org, but the task's exercise hangs off workshopA, not otherA.
        mockMvc.perform(post("/api/organizations/{orgId}/workshops/{wId}/assignments/{tId}/deal",
                        orgA.getId(), otherA.getId(), sortA.getId()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ fixtures

    private Organization newOrg(String name) {
        Organization org = new Organization();
        org.setName(name);
        org.setActive(true);
        return organizationRepository.save(org);
    }

    private Workshop newWorkshop(Organization org, String name) {
        Workshop w = new Workshop();
        w.setOrgId(org.getId());
        w.setName(name);
        w.setStatus(WorkshopStatus.DRAFT);
        return workshops.saveAndFlush(w);
    }

    private WorkshopExercise newExercise(Workshop workshop) {
        WorkshopExercise e = new WorkshopExercise();
        e.setWorkshopId(workshop.getId());
        e.setTitle("Exercise");
        return exercises.saveAndFlush(e);
    }

    private WorkshopExerciseTask newSortTask(WorkshopExercise exercise) {
        WorkshopExerciseTask t = new WorkshopExerciseTask();
        t.setExerciseId(exercise.getId());
        t.setTaskType(WorkshopTaskType.SORT);
        t.setAssignee(WorkshopTaskAssignee.LEAD);
        t.setTitle("Sort");
        t.setConfig(Map.of("cards", List.of(
                Map.of("id", "c1", "text", "Keep", "correct", "left"),
                Map.of("id", "c2", "text", "Drop", "correct", "right"))));
        return tasks.saveAndFlush(t);
    }
}
