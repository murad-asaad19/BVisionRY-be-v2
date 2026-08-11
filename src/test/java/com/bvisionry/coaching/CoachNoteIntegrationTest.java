package com.bvisionry.coaching;

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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The coach-notes authz matrix (spec §2.2): a coach writes notes only on
 * founders inside their assignment union and edits/deletes only their OWN
 * notes (someone else's note id reads as absent); org admins read notes on the
 * founder profile; members have no route at all.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class CoachNoteIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate jdbc;

    private Organization orgA;
    private User admin;
    private User coach;          // granted cohort1
    private User otherCoach;     // ALSO granted cohort1 — sees the founder, must not touch coach's notes
    private User founder;        // in cohort1
    private User founderOutside; // no grant reaches them

    @BeforeEach
    void seed() {
        orgA = saveOrg("Notes Org");
        admin = saveUser("admin@notes.invalid", UserRole.ORG_ADMIN, orgA);
        coach = saveUser("coach@notes.invalid", UserRole.COACH, orgA);
        otherCoach = saveUser("other.coach@notes.invalid", UserRole.COACH, orgA);
        founder = saveUser("founder@notes.invalid", UserRole.MEMBER, orgA);
        founderOutside = saveUser("outside@notes.invalid", UserRole.MEMBER, orgA);

        UUID cohort1 = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, name, status) VALUES (?, 'Cohort One', 'LAUNCHED')",
                cohort1);
        jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)",
                cohort1, orgA.getId());
        jdbc.update("INSERT INTO cohort_members (cohort_id, user_id) VALUES (?, ?)",
                cohort1, founder.getId());
        jdbc.update("INSERT INTO coach_assignments (org_id, coach_id, cohort_id) VALUES (?, ?, ?)",
                orgA.getId(), coach.getId(), cohort1);
        jdbc.update("INSERT INTO coach_assignments (org_id, coach_id, cohort_id) VALUES (?, ?, ?)",
                orgA.getId(), otherCoach.getId(), cohort1);
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    private String notesUrl(User target) {
        return "/api/v1/coach/founders/" + target.getId() + "/notes";
    }

    private static String body(String text) {
        return "{\"body\":\"%s\"}".formatted(text);
    }

    @Test
    void coachWritesEditsAndDeletesOwnNote_datedAndLabeled() throws Exception {
        TestAuthentication.authenticate(coach);
        String created = mockMvc.perform(post(notesUrl(founder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Needs a push on pricing.")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.coachId", is(coach.getId().toString())))
                .andExpect(jsonPath("$.coachName", is("coach")))
                .andExpect(jsonPath("$.body", is("Needs a push on pricing.")))
                // §7b: both timestamps in the contract from the first write.
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andExpect(jsonPath("$.updatedAt", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        UUID noteId = UUID.fromString(created.replaceAll(".*\"id\":\"([0-9a-f-]+)\".*", "$1"));

        mockMvc.perform(patch("/api/v1/coach/notes/" + noteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Pricing improved this week.")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body", is("Pricing improved this week.")));

        mockMvc.perform(delete("/api/v1/coach/notes/" + noteId))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM coach_notes", Integer.class))
                .isZero();
    }

    @Test
    void founderOutsideTheUnionIs404_andNothingIsWritten() throws Exception {
        TestAuthentication.authenticate(coach);
        mockMvc.perform(post(notesUrl(founderOutside))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Should never land.")))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM coach_notes", Integer.class))
                .isZero();
    }

    @Test
    void anotherCoachsNoteReadsAsAbsent_evenWithSharedFounderVisibility() throws Exception {
        UUID noteId = insertNote(coach, "Coach A's private judgement.");

        TestAuthentication.authenticate(otherCoach);
        mockMvc.perform(patch("/api/v1/coach/notes/" + noteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Hijacked.")))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/coach/notes/" + noteId))
                .andExpect(status().isNotFound());

        // Untouched — refused means not written.
        assertThat(jdbc.queryForObject("SELECT body FROM coach_notes WHERE id = ?",
                String.class, noteId)).isEqualTo("Coach A's private judgement.");
    }

    @Test
    void aRevokedCoachCanNoLongerTouchTheirOldNotes() throws Exception {
        UUID noteId = insertNote(coach, "Written while assigned.");
        // Admin revokes every grant this coach held.
        jdbc.update("DELETE FROM coach_assignments WHERE coach_id = ?", coach.getId());

        TestAuthentication.authenticate(coach);
        mockMvc.perform(patch("/api/v1/coach/notes/" + noteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Post-revocation edit.")))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/coach/notes/" + noteId))
                .andExpect(status().isNotFound());

        // The note itself survives — org admins still see it on the profile.
        assertThat(jdbc.queryForObject("SELECT body FROM coach_notes WHERE id = ?",
                String.class, noteId)).isEqualTo("Written while assigned.");
    }

    @Test
    void membersAndAdminsHaveNoWriteRoute() throws Exception {
        UUID noteId = insertNote(coach, "Kept.");

        TestAuthentication.authenticate(founder);
        mockMvc.perform(post(notesUrl(founder)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Self note?"))).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/coach/notes/" + noteId))
                .andExpect(status().isForbidden());

        TestAuthentication.authenticate(admin);
        mockMvc.perform(post(notesUrl(founder)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Admin note?"))).andExpect(status().isForbidden());
    }

    @Test
    void blankBodyIsRefused() throws Exception {
        TestAuthentication.authenticate(coach);
        mockMvc.perform(post(notesUrl(founder)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ")))
                .andExpect(status().isBadRequest());
    }

    /* ------------------------------------------------------------ seeding */

    private UUID insertNote(User author, String text) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO coach_notes (id, org_id, coach_id, member_id, body)
                VALUES (?, ?, ?, ?, ?)
                """, id, orgA.getId(), author.getId(), founder.getId(), text);
        return id;
    }

    private Organization saveOrg(String name) {
        Organization org = new Organization();
        org.setName(name);
        org.setActive(true);
        return organizationRepository.saveAndFlush(org);
    }

    private User saveUser(String email, UserRole role, Organization org) {
        User user = new User();
        user.setEmail(email);
        user.setName(email.substring(0, email.indexOf('@')));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setOrganization(org);
        return userRepository.saveAndFlush(user);
    }
}
