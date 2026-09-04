package com.bvisionry.gdpr;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.organization.MemberService;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GDPR Art. 15 export and Art. 17 erasure for the caller's own account.
 *
 * <p>The assertions that matter most are the ones about OTHER people: an export
 * must contain only the caller's rows and must not hand out third parties'
 * identities, and an erasure must leave every other tenant's record — and every
 * other person named in the caller's own audit trail — intact.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class GdprIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String MY_PASSWORD = "Password123!";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private MemberService memberService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Organization organization;
    private User me;
    private User teammate;
    private UUID myCourseId;
    private Organization otherOrg;
    private UUID otherOrgInvitation;
    private UUID myOrgInvitation;
    private UUID otherOrgPublicSubmission;
    private UUID otherOrgSurveyResponse;
    private UUID otherOrgAuditRow;
    private UUID otherOrgAuditRowByMemberId;
    private UUID myExerciseSubmissionId;
    private UUID teammateExerciseSubmissionId;
    private UUID subjectReviewCommentId;
    private UUID mySessionId;

    @BeforeEach
    void seed() {
        organization = new Organization();
        organization.setName("Erasure Test Org");
        organization.setSubscriptionTier(SubscriptionTier.GROWTH);
        organization.setActive(true);
        organization = organizationRepository.saveAndFlush(organization);

        me = persistUser("gdpr-me@t.invalid", "Data Subject", UserRole.MEMBER, MY_PASSWORD);
        teammate = persistUser("gdpr-teammate@t.invalid", "Teammate", UserRole.MEMBER, MY_PASSWORD);

        seedAssessment(me, "me@respondent.invalid");
        seedAssessment(teammate, "teammate@respondent.invalid");

        // A survey response NOT reachable through the submission cascade. Its
        // ck_workshop_intro_has_user_and_workshop CHECK forbids a null respondent,
        // so if erasure let the FK's ON DELETE SET NULL fire, the whole delete aborts.
        UUID workshopId = UUID.randomUUID();
        jdbc.update("INSERT INTO workshops (id, org_id, name) VALUES (?, ?, ?)",
                workshopId, organization.getId(), "Kickoff");
        UUID surveyId = UUID.randomUUID();
        jdbc.update("INSERT INTO surveys (id, name) VALUES (?, ?)", surveyId, "Intro survey");
        jdbc.update("""
                INSERT INTO survey_responses (id, survey_id, source, respondent_user_id, workshop_id,
                                              respondent_email, respondent_name, gift_token)
                VALUES (?, ?, 'WORKSHOP_INTRO', ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), surveyId, me.getId(), workshopId, me.getEmail(), me.getName(),
                UUID.randomUUID());

        jdbc.update("""
                INSERT INTO certificate (id, user_id, certificate_number, course_title, learner_name)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), me.getId(), "CERT-1", "Fundamentals", me.getName());

        myCourseId = UUID.randomUUID();
        jdbc.update("INSERT INTO course (id, org_id, slug, title) VALUES (?, ?, ?, ?)",
                myCourseId, organization.getId(), "fundamentals", "Fundamentals");
        jdbc.update("INSERT INTO review (id, org_id, course_id, user_id, author_name, rating) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), organization.getId(), myCourseId, me.getId(), me.getName(), 5);

        myOrgInvitation = UUID.randomUUID();
        jdbc.update("INSERT INTO invitations (id, email, organization_id, expires_at) VALUES (?,?,?,?)",
                myOrgInvitation, me.getEmail(), organization.getId(),
                Timestamp.from(Instant.now().plusSeconds(3600)));

        // An audit row ABOUT them: the FK only nulls actor_id, so without an explicit
        // scrub the trail keeps their email forever.
        insertAudit(UUID.randomUUID(), me.getId(), "USER_LOGIN", "Organization", organization.getId(),
                "{\"email\":\"gdpr-me@t.invalid\",\"provider\":\"password\"}");

        // A coach profile (V153). PersonalDataCoverageTest only proves the FK
        // was DECIDED about; this proves the export actually carries the row.
        jdbc.update("INSERT INTO coach_profiles (coach_id, booking_url) VALUES (?, ?)",
                me.getId(), "https://cal.com/gdpr-me");

        // Their own booking, held (V215/V216). sessions.member_id CASCADEs, but
        // session_attendance.session_id is RESTRICT (V212), so without an
        // explicit clear the whole account delete aborts on the roll call.
        UUID cohortId = UUID.randomUUID();
        jdbc.update("INSERT INTO cohorts (id, name, status) VALUES (?, 'Erasure Cohort', 'LAUNCHED')",
                cohortId);
        jdbc.update("INSERT INTO cohort_orgs (cohort_id, org_id) VALUES (?, ?)",
                cohortId, organization.getId());
        mySessionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sessions (id, cohort_id, type, title, session_date, ends_at,
                                      program_task_id, member_id, booking_status)
                VALUES (?, ?, 'COACHING_1ON1', 'Coaching 1:1', now() - interval '1 day',
                        now() - interval '23 hours', ?, ?, 'COMPLETED')
                """, mySessionId, cohortId, UUID.randomUUID(), me.getId());
        jdbc.update("INSERT INTO session_attendance (session_id, member_id) VALUES (?, ?)",
                mySessionId, me.getId());

        seedExerciseThread();
        seedOtherOrg();
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    // ---------------------------------------------------------------- Art. 15

    @Test
    void export_downloadsOnlyTheCallersOwnRows() throws Exception {
        TestAuthentication.authenticate(me);

        mockMvc.perform(get("/api/gdpr/me/export"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Content-Disposition",
                        startsWith("attachment; filename=\"bvisionry-my-data-")))
                .andExpect(jsonPath("$.userId", is(me.getId().toString())))
                .andExpect(jsonPath("$.data.account", hasSize(1)))
                .andExpect(jsonPath("$.data.account[0].email", is(me.getEmail())))
                // Credentials are never handed back in the file.
                .andExpect(jsonPath("$.data.account[0].password_hash").doesNotExist())
                // One submission — mine. The teammate's identical row must not appear.
                .andExpect(jsonPath("$.data.assessment_submissions", hasSize(1)))
                .andExpect(jsonPath("$.data.assessment_submissions[0].respondent_email",
                        is("me@respondent.invalid")))
                .andExpect(jsonPath("$.data.certificates", hasSize(1)))
                .andExpect(jsonPath("$.data.survey_responses", hasSize(1)))
                // The coach profile is content the subject authored about
                // themselves — drop it from EXPORT_SECTIONS and this fails.
                .andExpect(jsonPath("$.data.coach_profile", hasSize(1)))
                .andExpect(jsonPath("$.data.coach_profile[0].booking_url",
                        is("https://cal.com/gdpr-me")))
                // Sections with nothing in them are still present, so the shape is stable.
                .andExpect(jsonPath("$.data.quiz_attempts", hasSize(0)));
    }

    /**
     * A member's Art. 15 copy names their controller — it does not hand them the
     * org's commercial record, which OrganizationController gates to ORG_ADMIN+.
     */
    @Test
    void export_organizationSection_isNameOnly_notTheOrgsCommercialRecord() throws Exception {
        TestAuthentication.authenticate(me);

        mockMvc.perform(get("/api/gdpr/me/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.organization[0].name", is("Erasure Test Org")))
                .andExpect(jsonPath("$.data.organization[0].subscription_tier").doesNotExist())
                .andExpect(jsonPath("$.data.organization[0].trial_ends_at").doesNotExist())
                .andExpect(jsonPath("$.data.organization[0].is_active").doesNotExist())
                .andExpect(jsonPath("$.data.organization[0].description").doesNotExist())
                .andExpect(jsonPath("$.data.organization[0].parent_organization_id").doesNotExist());
    }

    @Test
    void export_carriesNoCredentialOrCapabilityToken() throws Exception {
        TestAuthentication.authenticate(me);

        mockMvc.perform(get("/api/gdpr/me/export"))
                .andExpect(status().isOk())
                // gift_token is a capability token the old exact-name denylist missed.
                .andExpect(jsonPath("$.data.survey_responses[0].gift_token").doesNotExist())
                .andExpect(jsonPath("$.data.survey_responses[0].ip_hash").doesNotExist())
                .andExpect(jsonPath("$.data.assessment_submissions[0].access_token").doesNotExist());
    }

    @Test
    void export_includesProfilingHistoryAndWorksheetContent() throws Exception {
        TestAuthentication.authenticate(me);

        mockMvc.perform(get("/api/gdpr/me/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pillar_evaluation_history").exists())
                .andExpect(jsonPath("$.data.overall_summary_history").exists())
                .andExpect(jsonPath("$.data.ai_call_logs", hasSize(1)))
                .andExpect(jsonPath("$.data.ai_call_logs[0].user_message", is("My private answer text")))
                // Our prompt template and the unparsed model output add nothing about
                // the person that the exported evaluations do not already carry.
                .andExpect(jsonPath("$.data.ai_call_logs[0].system_prompt").doesNotExist())
                .andExpect(jsonPath("$.data.ai_call_logs[0].raw_response").doesNotExist())
                .andExpect(jsonPath("$.data.exercise_rows", hasSize(1)))
                // Art. 15(4): a comment they left on review freezes ANOTHER member's
                // answer in cell_value_snapshot. Their comment body is theirs; the
                // snapshot is not.
                .andExpect(jsonPath("$.data.exercise_comments", hasSize(1)))
                .andExpect(jsonPath("$.data.exercise_comments[0].body", is("Reviewer note by subject")))
                .andExpect(jsonPath("$.data.exercise_comments[0].cell_value_snapshot").doesNotExist());
    }

    @Test
    void export_isScopedToTheCaller_notTheOrg() throws Exception {
        TestAuthentication.authenticate(teammate);

        mockMvc.perform(get("/api/gdpr/me/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assessment_submissions", hasSize(1)))
                .andExpect(jsonPath("$.data.assessment_submissions[0].respondent_email",
                        is("teammate@respondent.invalid")))
                // The certificate, review and survey response all belong to `me`.
                .andExpect(jsonPath("$.data.certificates", hasSize(0)))
                .andExpect(jsonPath("$.data.course_reviews", hasSize(0)))
                .andExpect(jsonPath("$.data.survey_responses", hasSize(0)));
    }

    @Test
    void export_isRateLimitedPerAccount() throws Exception {
        TestAuthentication.authenticate(me);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/gdpr/me/export")).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/gdpr/me/export")).andExpect(status().isTooManyRequests());
    }

    /**
     * The status is deliberately not pinned: this slice runs with
     * {@code addFilters = false}, so the request never meets the filter chain's
     * {@code anyRequest().authenticated()} that returns 401 in production — it
     * is stopped one layer later by {@code @PreAuthorize}. What must hold either
     * way is that an anonymous caller gets no data and deletes no account.
     */
    @Test
    void bothEndpoints_rejectAnUnauthenticatedCaller() throws Exception {
        TestAuthentication.clear();

        assertThat(mockMvc.perform(get("/api/gdpr/me/export"))
                .andReturn().getResponse().getStatus()).isNotIn(200, 204);
        assertThat(mockMvc.perform(deleteMe("gdpr-me@t.invalid", MY_PASSWORD))
                .andReturn().getResponse().getStatus()).isNotIn(200, 204);

        assertThat(countUsers(me.getId())).isOne();
    }

    // ---------------------------------------------------------------- Art. 17

    @Test
    void delete_wrongConfirmEmail_isRejected_andKeepsTheAccount() throws Exception {
        TestAuthentication.authenticate(me);

        mockMvc.perform(deleteMe("someone-else@t.invalid", MY_PASSWORD))
                .andExpect(status().isBadRequest());

        assertThat(countUsers(me.getId())).isOne();
    }

    /** Irreversible, so it must not demand less proof than a password change does. */
    @Test
    void delete_wrongOrMissingPassword_isRejected_andKeepsTheAccount() throws Exception {
        TestAuthentication.authenticate(me);

        mockMvc.perform(deleteMe("gdpr-me@t.invalid", "not-my-password"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(deleteMe("gdpr-me@t.invalid", null))
                .andExpect(status().isBadRequest());

        assertThat(countUsers(me.getId())).isOne();
    }

    /** SSO-only accounts have no hash to verify — email confirmation is the proof. */
    @Test
    void delete_ssoAccountWithNoPassword_succeedsOnEmailConfirmationAlone() throws Exception {
        User ssoUser = persistUser("gdpr-sso@t.invalid", "SSO User", UserRole.MEMBER, null);
        ssoUser.setSsoProvider("google");
        userRepository.saveAndFlush(ssoUser);
        TestAuthentication.authenticate(ssoUser);

        mockMvc.perform(deleteMe("gdpr-sso@t.invalid", null))
                .andExpect(status().isNoContent());

        assertThat(countUsers(ssoUser.getId())).isZero();
    }

    @Test
    void delete_erasesTheCallerAndTheirData_leavingOtherTenantsIntact() throws Exception {
        TestAuthentication.authenticate(me);

        mockMvc.perform(deleteMe("  GDPR-ME@T.INVALID ", MY_PASSWORD))
                .andExpect(status().isNoContent());

        assertThat(countUsers(me.getId())).isZero();
        // Cascades: their own assessment history goes with them.
        assertThat(count("SELECT count(*) FROM submissions WHERE user_id = ?", me.getId())).isZero();
        assertThat(count("SELECT count(*) FROM assignments WHERE user_id = ?", me.getId())).isZero();
        // Explicit erasure: rows the FK actions would have left holding their PII.
        // Their ACCOUNT-linked survey response goes; the public-link row that merely
        // carries the same address does not (no mailbox proof — see the class doc).
        assertThat(count("SELECT count(*) FROM survey_responses WHERE respondent_user_id = ?", me.getId()))
                .isZero();
        // NOT erased: an invitation is addressed to an unverified email, and
        // users.email is attacker-choosable — see the identity note on
        // PersonalDataRepository. Reaching it needs mailbox proof we do not have.
        assertThat(count("SELECT count(*) FROM invitations WHERE id = ?", myOrgInvitation)).isOne();
        // No FK reaches ai_call_logs — without the explicit delete their verbatim
        // answers would outlive the account.
        assertThat(count("SELECT count(*) FROM ai_call_logs WHERE user_message = ?", "My private answer text"))
                .isZero();
        // Their booking and its roll call go with them — the RESTRICT FK on
        // session_attendance would otherwise have aborted the whole delete.
        assertThat(count("SELECT count(*) FROM sessions WHERE id = ?", mySessionId)).isZero();
        assertThat(count("SELECT count(*) FROM session_attendance WHERE session_id = ?",
                mySessionId)).isZero();
        // Anonymised, not deleted: the course's rating/reviews_count aggregate survives.
        assertThat(count("SELECT count(*) FROM review WHERE course_id = ? AND user_id IS NULL "
                + "AND author_name = 'Deleted user'", myCourseId)).isOne();
        // Accountability: the erasure is recorded without retaining the email.
        entityManager.flush();
        assertThat(count("SELECT count(*) FROM audit_logs WHERE action_type = 'GDPR_ACCOUNT_DELETED' "
                + "AND entity_id = ?", me.getId())).isOne();
        // The other member is untouched.
        assertThat(countUsers(teammate.getId())).isOne();
        assertThat(count("SELECT count(*) FROM submissions WHERE user_id = ?", teammate.getId())).isOne();
    }

    /**
     * V85 made certificate.user_id SET NULL on purpose so the row outlives its
     * holder and the public verify-by-number lookup keeps working. Deleting the
     * row would 404 every number the person ever shared.
     */
    @Test
    void delete_anonymisesCertificates_soPublicVerificationStillResolves() throws Exception {
        TestAuthentication.authenticate(me);

        mockMvc.perform(deleteMe("gdpr-me@t.invalid", MY_PASSWORD)).andExpect(status().isNoContent());

        assertThat(count("SELECT count(*) FROM certificate WHERE certificate_number = 'CERT-1' "
                + "AND user_id IS NULL AND learner_name = 'Deleted user'")).isOne();
        mockMvc.perform(get("/api/v1/certificates/verify/{n}", "CERT-1"))
                .andExpect(status().isOk());
    }

    /**
     * The class of bug an actor-scoped audit scrub creates: rows the subject
     * AUTHORED name OTHER people. Erasing those is someone else's erasure.
     */
    @Test
    void delete_scrubsAuditRowsAboutTheSubject_butNeverTheOnesTheySimplyAuthored() throws Exception {
        User admin = persistUser("gdpr-admin@t.invalid", "Admin", UserRole.ORG_ADMIN, MY_PASSWORD);
        persistUser("gdpr-admin-2@t.invalid", "Second Admin", UserRole.ORG_ADMIN, MY_PASSWORD);

        // Authored BY the admin, ABOUT other people — must survive verbatim.
        UUID inviteRow = UUID.randomUUID();
        insertAudit(inviteRow, admin.getId(), "MEMBER_INVITED", "Organization", organization.getId(),
                "{\"email\":\"invitee@t.invalid\",\"role\":\"MEMBER\"}");
        UUID renameRow = UUID.randomUUID();
        insertAudit(renameRow, admin.getId(), "MEMBER_PROFILE_UPDATED", "Organization", organization.getId(),
                "{\"memberId\":\"" + teammate.getId() + "\",\"oldName\":\"Teammate\",\"newName\":\"Team Mate\"}");
        // About the admin themselves — must be scrubbed.
        UUID aboutAdmin = UUID.randomUUID();
        insertAudit(aboutAdmin, teammate.getId(), "MEMBER_STATUS_CHANGED", "User", admin.getId(),
                "{\"email\":\"gdpr-admin@t.invalid\",\"newStatus\":\"ACTIVE\"}");
        // Authored BY the admin and naming ONLY the admin, with no id in the blob
        // to match on — the shape UPGRADE_REQUESTED writes.
        UUID selfAuthored = UUID.randomUUID();
        insertAudit(selfAuthored, admin.getId(), "UPGRADE_REQUESTED", "Organization", organization.getId(),
                "{\"memberName\":\"Admin\",\"featureContext\":\"Insights\"}");
        // Same shape, same author, but naming somebody else — must survive.
        UUID aboutTeammate = UUID.randomUUID();
        insertAudit(aboutTeammate, admin.getId(), "ASSESSMENT_ASSIGNED", "Organization", organization.getId(),
                "{\"memberName\":\"Teammate\",\"pipelineName\":\"Readiness\"}");

        TestAuthentication.authenticate(admin);
        mockMvc.perform(deleteMe("gdpr-admin@t.invalid", MY_PASSWORD)).andExpect(status().isNoContent());

        // Third parties keep their names — the admin's erasure is not theirs.
        assertThat(detail(inviteRow, "email")).isEqualTo("invitee@t.invalid");
        assertThat(detail(renameRow, "oldName")).isEqualTo("Teammate");
        assertThat(detail(renameRow, "newName")).isEqualTo("Team Mate");
        // …and the rows keep their non-identity context too.
        assertThat(detail(inviteRow, "role")).isEqualTo("MEMBER");
        assertThat(detail(aboutTeammate, "memberName")).isEqualTo("Teammate");
        // The subject's own identity is gone, the row and its context are not.
        assertThat(detail(aboutAdmin, "email")).isNull();
        assertThat(detail(aboutAdmin, "newStatus")).isEqualTo("ACTIVE");
        assertThat(detail(selfAuthored, "memberName")).isNull();
        assertThat(detail(selfAuthored, "featureContext")).isEqualTo("Insights");
    }

    /**
     * author_id and parent_id both CASCADE, so deleting a reviewer would delete
     * their comments on OTHER members' submissions and drag those members' replies
     * down with them.
     */
    @Test
    void delete_keepsOtherMembersRepliesToTheSubjectsComments() throws Exception {
        // The teammate replies, on their OWN worksheet, to the subject's review note.
        UUID reply = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_comments (id, submission_id, author_id, parent_id, body)
                VALUES (?, ?, ?, ?, ?)
                """, reply, teammateExerciseSubmissionId, teammate.getId(), subjectReviewCommentId,
                "Teammate reply");

        TestAuthentication.authenticate(me);
        mockMvc.perform(deleteMe("gdpr-me@t.invalid", MY_PASSWORD)).andExpect(status().isNoContent());

        // The subject's own words go with them; the teammate's survive, re-rooted.
        assertThat(count("SELECT count(*) FROM exercise_comments WHERE id = ?", subjectReviewCommentId))
                .isZero();
        assertThat(count("SELECT count(*) FROM exercise_comments WHERE id = ? AND parent_id IS NULL", reply))
                .isOne();
        assertThat(count("SELECT count(*) FROM exercise_submissions WHERE id = ?",
                teammateExerciseSubmissionId)).isOne();
    }

    @Test
    void delete_soleActiveOrgAdmin_isRefusedWithTheUnblockingStep() throws Exception {
        User onlyAdmin = persistUser("gdpr-only-admin@t.invalid", "Only Admin", UserRole.ORG_ADMIN, MY_PASSWORD);
        TestAuthentication.authenticate(onlyAdmin);

        mockMvc.perform(deleteMe("gdpr-only-admin@t.invalid", MY_PASSWORD))
                .andExpect(status().isBadRequest());

        assertThat(countUsers(onlyAdmin.getId())).isOne();
    }

    @Test
    void delete_orgAdminWithAnotherAdminPresent_isAllowed() throws Exception {
        persistUser("gdpr-admin-a@t.invalid", "Admin A", UserRole.ORG_ADMIN, MY_PASSWORD);
        User leaving = persistUser("gdpr-admin-b@t.invalid", "Admin B", UserRole.ORG_ADMIN, MY_PASSWORD);
        TestAuthentication.authenticate(leaving);

        mockMvc.perform(deleteMe("gdpr-admin-b@t.invalid", MY_PASSWORD))
                .andExpect(status().isNoContent());

        assertThat(countUsers(leaving.getId())).isZero();
    }

    // ------------------------------------------------------- identity boundary

    /**
     * The attack the email-keyed arms opened: {@code POST /api/auth/register} is
     * permitAll and mints an ACTIVE account for any address with no existing row,
     * with no mailbox proof. So an email is not an identity — a squatter who
     * registers an address must reach none of the data that address left behind.
     */
    @Test
    void squatter_registeringAnUnclaimedAddress_readsAndErasesNoneOfItsData() throws Exception {
        String squatted = "squatted@t.invalid";
        UUID publicSubmission = seedPublicSubmission(squatted, "Real Person");
        UUID surveyId = UUID.randomUUID();
        jdbc.update("INSERT INTO surveys (id, name) VALUES (?, ?)", surveyId, "Public survey");
        UUID publicResponse = UUID.randomUUID();
        jdbc.update("INSERT INTO survey_responses (id, survey_id, source, respondent_email) "
                        + "VALUES (?,?,'PUBLIC_LINK',?)", publicResponse, surveyId, squatted);
        UUID invite = UUID.randomUUID();
        jdbc.update("INSERT INTO invitations (id, email, organization_id, expires_at) VALUES (?,?,?,?)",
                invite, squatted, organization.getId(), Timestamp.from(Instant.now().plusSeconds(3600)));
        // An audit row in an org the squatter has nothing to do with, naming the
        // address they are about to claim. This is the fixture that goes red the
        // moment any email-value predicate comes back into the erasure.
        UUID otherOrgRowNamingSquatted = UUID.randomUUID();
        insertAudit(otherOrgRowNamingSquatted, null, "MEMBER_INVITED", "Organization", otherOrg.getId(),
                "{\"email\":\"" + squatted + "\",\"role\":\"MEMBER\"}");

        // Anyone can do this today: no mailbox proof anywhere in registration.
        User squatter = persistUser(squatted, "Squatter", UserRole.MEMBER, MY_PASSWORD);
        TestAuthentication.authenticate(squatter);

        mockMvc.perform(get("/api/gdpr/me/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assessment_submissions", hasSize(0)))
                .andExpect(jsonPath("$.data.survey_responses", hasSize(0)))
                .andExpect(jsonPath("$.data.invitations").doesNotExist());

        mockMvc.perform(deleteMe(squatted, MY_PASSWORD)).andExpect(status().isNoContent());

        assertThat(count("SELECT count(*) FROM submissions WHERE id = ? AND respondent_name = 'Real Person'",
                publicSubmission)).isOne();
        assertThat(count("SELECT count(*) FROM survey_responses WHERE id = ?", publicResponse)).isOne();
        assertThat(count("SELECT count(*) FROM invitations WHERE id = ?", invite)).isOne();
        // …and the audit trail of an unrelated org still names the address the
        // squatter claimed: registering it bought them nothing, anywhere.
        assertThat(detail(otherOrgRowNamingSquatted, "email")).isEqualTo(squatted);
    }

    /**
     * The gap that let two cycles of cross-tenant findings through: every other
     * fixture uses one org. Neither erasure path may touch a second org's rows.
     */
    @Test
    void adminPermanentDelete_touchesNothingInAnotherOrganisation() {
        User admin = persistUser("gdpr-org-admin@t.invalid", "Org Admin", UserRole.ORG_ADMIN, MY_PASSWORD);

        memberService.deleteMemberPermanently(organization.getId(), me.getId(), admin.getId());
        entityManager.flush();

        assertOtherOrgUntouched();
        // Identical to the self-service path: no email-value predicate exists, so
        // org B's invite record keeps the address...
        assertThat(detail(otherOrgAuditRow, "email")).isEqualTo(me.getEmail());
        // ...while the row naming them by id is scrubbed. Crossing the org line on
        // an unforgeable key is the intended behaviour; doing it on a chosen value
        // is what both paths refuse.
        assertThat(detail(otherOrgAuditRowByMemberId, "oldName")).isNull();
        assertThat(detail(otherOrgAuditRowByMemberId, "bulk")).isEqualTo("true");
    }

    @Test
    void selfServiceDelete_touchesNothingInAnotherOrganisation() throws Exception {
        TestAuthentication.authenticate(me);

        mockMvc.perform(deleteMe("gdpr-me@t.invalid", MY_PASSWORD)).andExpect(status().isNoContent());

        assertOtherOrgUntouched();
        // No email-value predicate exists on either path, so an org the subject
        // never joined keeps the address in its invite record. Documented on
        // PersonalDataRepository as the gap that needs mailbox proof, not an
        // oversight: reaching it means matching a key an attacker can choose.
        assertThat(detail(otherOrgAuditRow, "email")).isEqualTo(me.getEmail());
        // Scrubbed even so, because it names them by id — the unforgeable arm.
        assertThat(detail(otherOrgAuditRowByMemberId, "oldName")).isNull();
        assertThat(detail(otherOrgAuditRowByMemberId, "bulk")).isEqualTo("true");
    }

    /** The delete is a bcrypt oracle unless attempts — not successes — are metered. */
    @Test
    void delete_reauthenticationIsThrottled() throws Exception {
        TestAuthentication.authenticate(me);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(deleteMe("gdpr-me@t.invalid", "wrong-" + i))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(deleteMe("gdpr-me@t.invalid", "wrong-6"))
                .andExpect(status().isTooManyRequests());
        // Even the correct password is refused while the window is closed.
        mockMvc.perform(deleteMe("gdpr-me@t.invalid", MY_PASSWORD))
                .andExpect(status().isTooManyRequests());

        assertThat(countUsers(me.getId())).isOne();
    }

    // ------------------------------------------------- the other erasure caller

    /**
     * The admin path shares the same erasure component. Before it did, a bare
     * {@code userRepository.delete} aborted on any member with a survey response
     * (the CHECK constraints reject the null the FK writes) and left their name
     * and email in certificates, reviews and audit details.
     */
    @Test
    void adminPermanentDelete_ofAMemberWithSurveyResponses_succeedsAndErasesTheSameWay() {
        User admin = persistUser("gdpr-org-admin@t.invalid", "Org Admin", UserRole.ORG_ADMIN, MY_PASSWORD);

        memberService.deleteMemberPermanently(organization.getId(), me.getId(), admin.getId());
        entityManager.flush();

        assertThat(countUsers(me.getId())).isZero();
        // Their ACCOUNT-linked survey response goes; the public-link row that merely
        // carries the same address does not (no mailbox proof — see the class doc).
        assertThat(count("SELECT count(*) FROM survey_responses WHERE respondent_user_id = ?", me.getId()))
                .isZero();
        assertThat(count("SELECT count(*) FROM invitations WHERE id = ?", myOrgInvitation)).isOne();
        assertThat(count("SELECT count(*) FROM certificate WHERE learner_name = ?", me.getName())).isZero();
        assertThat(count("SELECT count(*) FROM review WHERE user_id IS NULL "
                + "AND author_name = 'Deleted user' AND course_id = ?", myCourseId)).isOne();
        assertThat(count("SELECT count(*) FROM ai_call_logs WHERE user_message = ?", "My private answer text"))
                .isZero();
    }

    // ---------------------------------------------------------------- helpers

    /** A second tenant holding exactly the shapes an unscoped statement would hit. */
    private void seedOtherOrg() {
        otherOrg = new Organization();
        otherOrg.setName("Other Org");
        otherOrg.setActive(true);
        otherOrg = organizationRepository.saveAndFlush(otherOrg);

        otherOrgInvitation = UUID.randomUUID();
        jdbc.update("INSERT INTO invitations (id, email, organization_id, expires_at) VALUES (?,?,?,?)",
                otherOrgInvitation, me.getEmail(), otherOrg.getId(),
                Timestamp.from(Instant.now().plusSeconds(3600)));

        otherOrgPublicSubmission = seedPublicSubmission(me.getEmail(), "Public Respondent");

        UUID surveyId = UUID.randomUUID();
        jdbc.update("INSERT INTO surveys (id, name) VALUES (?, ?)", surveyId, "Other org survey");
        otherOrgSurveyResponse = UUID.randomUUID();
        jdbc.update("INSERT INTO survey_responses (id, survey_id, source, respondent_email) "
                        + "VALUES (?,?,'PUBLIC_LINK',?)", otherOrgSurveyResponse, surveyId, me.getEmail());

        otherOrgAuditRow = UUID.randomUUID();
        insertAudit(otherOrgAuditRow, null, "MEMBER_INVITED", "Organization", otherOrg.getId(),
                "{\"email\":\"gdpr-me@t.invalid\",\"role\":\"MEMBER\"}");

        // Same other org, but named by an arm nobody can forge: a user id. This
        // one IS scrubbed, on both paths, and that is the intended property —
        // the erasure crosses org lines only on keys an actor cannot choose.
        otherOrgAuditRowByMemberId = UUID.randomUUID();
        insertAudit(otherOrgAuditRowByMemberId, null, "MEMBER_PROFILE_UPDATED", "Organization",
                otherOrg.getId(),
                "{\"memberId\":\"" + me.getId() + "\",\"oldName\":\"Data Subject\",\"bulk\":\"true\"}");
    }

    /** A submission taken through a public link: no user_id, anchored by the link. */
    private UUID seedPublicSubmission(String email, String name) {
        UUID pipelineId = UUID.randomUUID();
        jdbc.update("INSERT INTO pipelines (id, name) VALUES (?,?)", pipelineId, "Public " + email + name);
        UUID linkId = UUID.randomUUID();
        jdbc.update("INSERT INTO public_assessment_links (id, token, pipeline_id, title) VALUES (?,?,?,?)",
                linkId, UUID.randomUUID(), pipelineId, "Public link");
        UUID submissionId = UUID.randomUUID();
        jdbc.update("INSERT INTO submissions (id, public_link_id, respondent_email, respondent_name) "
                        + "VALUES (?,?,?,?)", submissionId, linkId, email, name);
        return submissionId;
    }

    private void assertOtherOrgUntouched() {
        assertThat(count("SELECT count(*) FROM invitations WHERE id = ?", otherOrgInvitation)).isOne();
        assertThat(count("SELECT count(*) FROM submissions WHERE id = ? AND respondent_name = ?",
                otherOrgPublicSubmission, "Public Respondent")).isOne();
        assertThat(count("SELECT count(*) FROM survey_responses WHERE id = ?", otherOrgSurveyResponse))
                .isOne();
        assertThat(count("SELECT count(*) FROM audit_logs WHERE id = ?", otherOrgAuditRow)).isOne();
    }

    private RequestBuilder deleteMe(String email, String password) {
        String body = password == null
                ? "{\"confirmEmail\":\"%s\"}".formatted(email)
                : "{\"confirmEmail\":\"%s\",\"currentPassword\":\"%s\"}".formatted(email, password);
        return delete("/api/gdpr/me").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private User persistUser(String email, String name, UserRole role, String rawPassword) {
        User user = new User();
        user.setEmail(email);
        user.setName(name);
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setOrganization(organization);
        if (rawPassword != null) {
            user.setPasswordHash(passwordEncoder.encode(rawPassword));
        }
        return userRepository.saveAndFlush(user);
    }

    private void seedAssessment(User user, String respondentEmail) {
        UUID pipelineId = UUID.randomUUID();
        jdbc.update("INSERT INTO pipelines (id, name) VALUES (?, ?)", pipelineId, "Pipeline " + respondentEmail);
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO assignments (id, pipeline_id, organization_id, user_id) VALUES (?,?,?,?)",
                assignmentId, pipelineId, organization.getId(), user.getId());
        UUID submissionId = UUID.randomUUID();
        jdbc.update("INSERT INTO submissions (id, assignment_id, user_id, respondent_email, access_token) "
                        + "VALUES (?,?,?,?,?)",
                submissionId, assignmentId, user.getId(), respondentEmail, UUID.randomUUID());
        if (user == me) {
            jdbc.update("""
                    INSERT INTO ai_call_logs (id, submission_id, call_type, model, called_at, elapsed_ms,
                                              status, system_prompt, user_message, raw_response)
                    VALUES (?, ?, 'PILLAR', 'test-model', now(), 10, 'SUCCESS', ?, ?, ?)
                    """, UUID.randomUUID(), submissionId, "our template",
                    "My private answer text", "{\"score\":1}");
        }
    }

    /**
     * Two worksheets: the subject's own (whose rows are theirs to export) and the
     * TEAMMATE's, on which the subject left a review comment. The second one is
     * what makes the cascade dangerous — the comment is the subject's, but the
     * worksheet and any replies are not.
     */
    private void seedExerciseThread() {
        UUID templateId = UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_templates (id, name, created_by) VALUES (?,?,?)",
                templateId, "Template", teammate.getId());

        myExerciseSubmissionId = seedExerciseSubmission(templateId, me);
        jdbc.update("INSERT INTO exercise_rows (id, submission_id, cells) VALUES (?,?,?::jsonb)",
                UUID.randomUUID(), myExerciseSubmissionId, "{\"c1\":\"my worksheet answer\"}");

        teammateExerciseSubmissionId = seedExerciseSubmission(templateId, teammate);
        subjectReviewCommentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO exercise_comments (id, submission_id, author_id, body, cell_value_snapshot)
                VALUES (?, ?, ?, ?, ?)
                """, subjectReviewCommentId, teammateExerciseSubmissionId, me.getId(),
                "Reviewer note by subject", "another member's answer verbatim");
    }

    private UUID seedExerciseSubmission(UUID templateId, User owner) {
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_assignments (id, template_id, organization_id, assigned_by, user_id) "
                        + "VALUES (?,?,?,?,?)",
                assignmentId, templateId, organization.getId(), teammate.getId(), owner.getId());
        UUID submissionId = UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_submissions (id, assignment_id, user_id) VALUES (?,?,?)",
                submissionId, assignmentId, owner.getId());
        return submissionId;
    }

    private void insertAudit(UUID id, UUID actorId, String action, String entityType, UUID entityId,
                             String detailsJson) {
        jdbc.update("""
                INSERT INTO audit_logs (id, actor_id, action_type, entity_type, entity_id, details_json)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, id, actorId, action, entityType, entityId, detailsJson);
    }

    private String detail(UUID auditRowId, String key) {
        return jdbc.queryForObject("SELECT details_json->>? FROM audit_logs WHERE id = ?",
                String.class, key, auditRowId);
    }

    private int countUsers(UUID userId) {
        return count("SELECT count(*) FROM users WHERE id = ?", userId);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
