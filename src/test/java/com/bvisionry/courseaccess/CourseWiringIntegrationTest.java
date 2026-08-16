package com.bvisionry.courseaccess;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.coursevisibility.CourseVisibilityAccess;
import com.bvisionry.common.enums.EnrollmentSource;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.courseaccess.domain.EffectiveCourse;
import com.bvisionry.courseaccess.domain.EffectiveCourseStatus;
import com.bvisionry.courseaccess.dto.AssignCourseRequest;
import com.bvisionry.courseaccess.dto.MemberCourseView;
import com.bvisionry.courseaccess.dto.OrgCourseRow;
import com.bvisionry.courseaccess.dto.UpdateCourseVisibilityRequest;
import com.bvisionry.courseaccess.dto.UpdateOrgCourseRequest;
import com.bvisionry.courseaccess.web.CourseAccessService;
import com.bvisionry.courseaccess.web.CourseVisibilityService;
import com.bvisionry.courseaccess.web.OrgCourseService;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.programflow.repository.TaskSpineRepository;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec §3 end to end against the real schema: read-time org rules, exclusions,
 * visibility, Suggest→Accept and lazy materialization.
 *
 * <p>The parts a pure unit test cannot reach — the precedence merge itself is
 * covered without a database in {@link EffectiveCoursesTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfDockerAvailable
class CourseWiringIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private CourseAccessService courseAccess;
    @Autowired private OrgCourseService orgCourses;
    @Autowired private CourseVisibilityService visibilityService;
    @Autowired private CourseVisibilityAccess visibility;
    @Autowired private TaskSpineRepository spine;
    @Autowired private OrganizationRepository orgs;
    @Autowired private UserRepository users;
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private com.bvisionry.enrollment.web.EnrollmentService enrollmentService;

    private Organization org;
    private User admin;
    private User member;
    private UUID courseId;
    private String slug;

    @BeforeEach
    void seed() {
        org = new Organization();
        org.setName("Course Wiring Org");
        org.setSubscriptionTier(SubscriptionTier.STARTER);
        org.setActive(true);
        org = orgs.saveAndFlush(org);

        admin = user("wiring-admin", UserRole.ORG_ADMIN);
        member = user("wiring-member", UserRole.MEMBER);
        // removeForEveryone stamps removed_by from the security context (its
        // DELETE endpoint carries no actor in the body); tests that need a
        // different principal re-authenticate themselves.
        com.bvisionry.testsupport.TestAuthentication.authenticate(admin);
        slug = "pricing-foundations-" + UUID.randomUUID();
        courseId = course("Pricing Foundations", slug);
    }

    /* ------------------------------------------------- org rules at read time */

    @Test
    void anOrgRuleCoversAMemberWithNoEnrollmentRow_andANewMemberToo() {
        orgCourses.assign(org.getId(),
                new AssignCourseRequest(courseId, AssignCourseRequest.AUDIENCE_ORG, null, true, null),
                admin.getId());

        EffectiveCourse row = only(courseAccess.effectiveCoursesOf(member.getId(), org.getId()));
        assertThat(row.source()).isEqualTo(EnrollmentSource.ORG_RULE);
        assertThat(row.status()).isEqualTo(EffectiveCourseStatus.ASSIGNED);
        assertThat(row.required()).isTrue();
        assertThat(row.materialized()).isFalse();
        assertThat(row.assignedByName()).isEqualTo(admin.getName());

        // The whole point of a rule: someone who joins AFTER it was written is
        // covered without a backfill.
        User joinedLater = user("wiring-newcomer", UserRole.MEMBER);
        assertThat(only(courseAccess.effectiveCoursesOf(joinedLater.getId(), org.getId())).source())
                .isEqualTo(EnrollmentSource.ORG_RULE);

        // ...and one delete uncovers everyone.
        orgCourses.removeForEveryone(org.getId(), courseId, "ORG_RULE");
        assertThat(courseAccess.effectiveCoursesOf(member.getId(), org.getId())).isEmpty();
    }

    @Test
    void anExclusionBeatsTheRule_andSurvivesForThatMemberOnly() {
        orgCourses.assign(org.getId(),
                new AssignCourseRequest(courseId, AssignCourseRequest.AUDIENCE_ORG, null, false, null),
                admin.getId());
        User other = user("wiring-other", UserRole.MEMBER);

        orgCourses.removeForMember(org.getId(), member.getId(), courseId, "not relevant", admin.getId());

        assertThat(courseAccess.effectiveCoursesOf(member.getId(), org.getId())).isEmpty();
        assertThat(courseAccess.effectiveCoursesOf(other.getId(), org.getId())).hasSize(1);
    }

    @Test
    void anExclusionSurvivesTheRuleBeingDeletedAndReCreated() {
        // DECIDED (review #6): an opt-out is a statement about a PERSON, not
        // about the rule instance that was live when it was made. Clearing it on
        // delete would silently re-add someone the moment the rule came back.
        orgCourses.assign(org.getId(),
                new AssignCourseRequest(courseId, AssignCourseRequest.AUDIENCE_ORG, null, false, null),
                admin.getId());
        orgCourses.removeForMember(org.getId(), member.getId(), courseId, null, admin.getId());
        orgCourses.removeForEveryone(org.getId(), courseId, "ORG_RULE");

        orgCourses.assign(org.getId(),
                new AssignCourseRequest(courseId, AssignCourseRequest.AUDIENCE_ORG, null, false, null),
                admin.getId());

        assertThat(courseAccess.effectiveCoursesOf(member.getId(), org.getId())).isEmpty();
    }

    @Test
    void aDirectAssignmentClearsAPreviousExclusion() {
        orgCourses.assign(org.getId(),
                new AssignCourseRequest(courseId, AssignCourseRequest.AUDIENCE_ORG, null, false, null),
                admin.getId());
        orgCourses.removeForMember(org.getId(), member.getId(), courseId, null, admin.getId());

        orgCourses.assign(org.getId(), new AssignCourseRequest(courseId,
                AssignCourseRequest.AUDIENCE_MEMBERS, List.of(member.getId()), true, null), admin.getId());

        EffectiveCourse row = only(courseAccess.effectiveCoursesOf(member.getId(), org.getId()));
        assertThat(row.source()).isEqualTo(EnrollmentSource.DIRECT);
        assertThat(row.required()).isTrue();
        assertThat(row.materialized()).isTrue();
    }

    @Test
    void directBeatsTheRule_andRemovingTheDirectExcludesTheMemberFromTheRuleToo() {
        Instant deadline = Instant.now().plus(30, ChronoUnit.DAYS);
        orgCourses.assign(org.getId(),
                new AssignCourseRequest(courseId, AssignCourseRequest.AUDIENCE_ORG, null, true, null),
                admin.getId());
        orgCourses.assign(org.getId(), new AssignCourseRequest(courseId,
                        AssignCourseRequest.AUDIENCE_MEMBERS, List.of(member.getId()), false, deadline),
                admin.getId());

        EffectiveCourse row = only(courseAccess.effectiveCoursesOf(member.getId(), org.getId()));
        assertThat(row.source()).isEqualTo(EnrollmentSource.DIRECT);
        // required is a UNION: the optional direct assignment cannot cancel the rule.
        assertThat(row.required()).isTrue();
        assertThat(row.deadline()).isNotNull();

        orgCourses.removeForEveryone(org.getId(), courseId, "DIRECT");
        // Operator decision 2026-08-14: "remove for everyone" HOLDS. The override
        // row it writes is a member-level exclusion, so it beats the org rule
        // still standing — the member does not silently fall back onto it.
        assertThat(courseAccess.effectiveCoursesOf(member.getId(), org.getId())).isEmpty();
    }

    /* ------------------------------------------------------- required §11 */

    @Test
    void reAssigningARuleKeepsWhoFirstMadeTheDecisionAndStampsWhoChangedIt() {
        orgCourses.assign(org.getId(),
                new AssignCourseRequest(courseId, AssignCourseRequest.AUDIENCE_ORG, null, false, null),
                admin.getId());
        User second = user("wiring-second-admin", UserRole.ORG_ADMIN);

        orgCourses.assign(org.getId(),
                new AssignCourseRequest(courseId, AssignCourseRequest.AUDIENCE_ORG, null, true, null),
                second.getId());

        assertThat(jdbc.queryForObject(
                "SELECT created_by FROM org_course_rules WHERE org_id = :o AND course_id = :c",
                new MapSqlParameterSource("o", org.getId()).addValue("c", courseId), UUID.class))
                .isEqualTo(admin.getId());
        // The §7b stamp the tab shows is the LATEST hand on it.
        assertThat(only(courseAccess.effectiveCoursesOf(member.getId(), org.getId()))
                .assignedByName()).isEqualTo(second.getName());
    }

    @Test
    void requiredIsMutableAfterAssignment() {
        orgCourses.assign(org.getId(),
                new AssignCourseRequest(courseId, AssignCourseRequest.AUDIENCE_ORG, null, true, null),
                admin.getId());
        // Materialize the rule for this member: the update must reach the row
        // too, or EffectiveCourses.merge (required OR'd, earliest deadline)
        // keeps serving the stricter old value to exactly the members who
        // engaged with the course.
        spine.ensureEnrollment(member.getId(), courseId);

        orgCourses.update(org.getId(), courseId, new UpdateOrgCourseRequest("ORG_RULE", false, null));
        EffectiveCourse relaxed = only(courseAccess.effectiveCoursesOf(member.getId(), org.getId()));
        assertThat(relaxed.required()).isFalse();
        assertThat(relaxed.deadline()).isNull();

        orgCourses.update(org.getId(), courseId, new UpdateOrgCourseRequest("ORG_RULE", true, null));
        assertThat(only(courseAccess.effectiveCoursesOf(member.getId(), org.getId())).required()).isTrue();
    }

    /* --------------------------------------------------- lazy materialization */

    @Test
    void aRuleDerivedCourseShowsInTheJourneyAndMaterializesOnOpen() {
        orgCourses.assign(org.getId(),
                new AssignCourseRequest(courseId, AssignCourseRequest.AUDIENCE_ORG, null, true, null),
                admin.getId());

        // Journey "Direct assignments" unions the rule even with no enrollment row.
        var before = spine.directCourses(member.getId());
        assertThat(before).singleElement().satisfies(c -> {
            assertThat(c.courseId()).isEqualTo(courseId);
            assertThat(c.enrollmentId()).isNull();
            assertThat(c.source()).isEqualTo("ORG_RULE");
            assertThat(c.required()).isTrue();
        });

        spine.ensureEnrollment(member.getId(), courseId);

        var after = spine.directCourses(member.getId());
        assertThat(after).singleElement().satisfies(c -> {
            assertThat(c.enrollmentId()).isNotNull();
            // Claim-aware stamping: a rule covers this member, so the row records
            // ORG_RULE — which is what lets the rule's delete still reach it.
            assertThat(c.source()).isEqualTo("ORG_RULE");
        });
        // Exactly one row — the union must not double-count after materializing.
        assertThat(after).hasSize(1);
    }

    @Test
    void unassigningARuleAlsoCancelsTheMembersWhoAlreadyOpenedIt() {
        orgCourses.assign(org.getId(),
                new AssignCourseRequest(courseId, AssignCourseRequest.AUDIENCE_ORG, null, true, null),
                admin.getId());
        spine.ensureEnrollment(member.getId(), courseId);
        assertThat(courseAccess.effectiveCoursesOf(member.getId(), org.getId())).hasSize(1);

        orgCourses.removeForEveryone(org.getId(), courseId, "ORG_RULE");

        // "Unassignment is one delete" holds for the opened member too.
        assertThat(courseAccess.effectiveCoursesOf(member.getId(), org.getId())).isEmpty();
        assertThat(spine.directCourses(member.getId())).isEmpty();
    }

    /* ------------------------------------------- remove-for-everyone holds */

    @Test
    void removeForEveryoneWritesAnOverridePerAffectedMember_soOneClickCannotUndoIt() {
        User second = user("wiring-second", UserRole.MEMBER);
        // Two self-enrolled members (SELF is the column default, like the endpoint writes).
        selfEnroll(member.getId());
        selfEnroll(second.getId());

        orgCourses.removeForEveryone(org.getId(), courseId, "SELF");

        // One removed-by-admin override row per member the cancel hit, stamped
        // with who did it — the same shape removeForMember writes.
        assertThat(jdbc.queryForList(
                "SELECT user_id FROM enrolment_overrides WHERE course_id = :c",
                new MapSqlParameterSource("c", courseId), UUID.class))
                .containsExactlyInAnyOrder(member.getId(), second.getId());
        assertThat(jdbc.queryForObject(
                "SELECT removed_by FROM enrolment_overrides WHERE course_id = :c AND user_id = :u",
                new MapSqlParameterSource("c", courseId).addValue("u", member.getId()), UUID.class))
                .isEqualTo(admin.getId());

        // The override is what makes the removal HOLD: without it,
        // reactivateIfRemoved would restore the CANCELLED row in one click.
        com.bvisionry.testsupport.TestAuthentication.authenticate(member);
        assertThatThrownBy(() -> enrollmentService.enroll(slug))
                .isInstanceOf(BadRequestException.class);
        assertThat(courseAccess.effectiveCoursesOf(member.getId(), org.getId())).isEmpty();
    }

    /**
     * V184: the blanket exclusions an org-wide removal stamps (scope ORG) are
     * undone by the next org-wide assign — otherwise the members who had
     * started the course would be the only ones locked out of a re-assign —
     * while a by-name removal (scope MEMBER) is about a person and holds.
     */
    @Test
    void anOrgWideReAssignUndoesItsOwnOrgWideRemoval_butNotAByNameOne() {
        User second = user("wiring-second", UserRole.MEMBER);
        orgCourses.assign(org.getId(),
                new AssignCourseRequest(courseId, AssignCourseRequest.AUDIENCE_ORG, null, true, null),
                admin.getId());
        spine.ensureEnrollment(member.getId(), courseId);
        orgCourses.removeForMember(org.getId(), second.getId(), courseId, "not relevant", admin.getId());
        orgCourses.removeForEveryone(org.getId(), courseId, "ORG_RULE");
        assertThat(courseAccess.effectiveCoursesOf(member.getId(), org.getId())).isEmpty();

        orgCourses.assign(org.getId(),
                new AssignCourseRequest(courseId, AssignCourseRequest.AUDIENCE_ORG, null, true, null),
                admin.getId());

        assertThat(courseAccess.effectiveCoursesOf(member.getId(), org.getId()))
                .as("the member who had started the course gets it back with everyone else")
                .hasSize(1);
        assertThat(courseAccess.effectiveCoursesOf(second.getId(), org.getId()))
                .as("a by-name removal survives the org-wide re-assign")
                .isEmpty();
    }

    @Test
    void anUnknownSourceIsRefusedWithA400_andCancelsNothing() {
        selfEnroll(member.getId());

        // of() would degrade the typo'd "ORG-RULE" to SELF and cancel every
        // self-enrolment in the org; strictOf refuses the request instead.
        assertThatThrownBy(() -> orgCourses.removeForEveryone(org.getId(), courseId, "ORG-RULE"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> orgCourses.update(org.getId(), courseId,
                new UpdateOrgCourseRequest("ORG-RULE", true, null)))
                .isInstanceOf(BadRequestException.class);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM enrollment WHERE user_id = :u AND course_id = :c",
                new MapSqlParameterSource("u", member.getId()).addValue("c", courseId), String.class))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM enrolment_overrides WHERE course_id = :c",
                new MapSqlParameterSource("c", courseId), Integer.class))
                .isZero();
    }

    @Test
    void aCohortTaskCourseNoRuleCoversIsStampedDirect() {
        spine.ensureEnrollment(member.getId(), courseId);

        assertThat(only(courseAccess.effectiveCoursesOf(member.getId(), org.getId())).source())
                .isEqualTo(EnrollmentSource.DIRECT);
    }

    /* ------------------------------------------------------------ visibility */

    @Test
    void minTierVisibility_isABoundaryOnTheOrgsOwnTier() {
        visibilityService.update(courseId,
                new UpdateCourseVisibilityRequest("MIN_TIER", "GROWTH", null), admin.getId());

        // STARTER < GROWTH: hidden.
        assertThat(visibility.isVisibleToOrg(courseId, org.getId())).isFalse();

        org.setSubscriptionTier(SubscriptionTier.GROWTH);
        orgs.saveAndFlush(org);
        // Exactly AT the boundary: visible.
        assertThat(visibility.isVisibleToOrg(courseId, org.getId())).isTrue();

        org.setSubscriptionTier(SubscriptionTier.FOUNDER_SUCCESS);
        orgs.saveAndFlush(org);
        assertThat(visibility.isVisibleToOrg(courseId, org.getId())).isTrue();
    }

    @Test
    void orgListVisibility_isExactlyTheListedOrgs() {
        Organization other = new Organization();
        other.setName("Other Org");
        other.setSubscriptionTier(SubscriptionTier.FOUNDER_SUCCESS);
        other.setActive(true);
        other = orgs.saveAndFlush(other);

        visibilityService.update(courseId,
                new UpdateCourseVisibilityRequest("ORG_LIST", null, List.of(other.getId())), admin.getId());

        assertThat(visibility.isVisibleToOrg(courseId, other.getId())).isTrue();
        assertThat(visibility.isVisibleToOrg(courseId, org.getId())).isFalse();

        // Replacing the list is wholesale — an unticked org really goes away.
        visibilityService.update(courseId,
                new UpdateCourseVisibilityRequest("ORG_LIST", null, List.of(org.getId())), admin.getId());
        assertThat(visibility.isVisibleToOrg(courseId, other.getId())).isFalse();
        assertThat(visibility.isVisibleToOrg(courseId, org.getId())).isTrue();
    }

    @Test
    void anOrgAdminCannotAssignACourseTheyCannotSee() {
        visibilityService.update(courseId,
                new UpdateCourseVisibilityRequest("ORG_LIST", null, List.of()), admin.getId());

        assertThatThrownBy(() -> orgCourses.assign(org.getId(),
                new AssignCourseRequest(courseId, AssignCourseRequest.AUDIENCE_ORG, null, false, null),
                admin.getId()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void memberLibraryCatalogIsTheVisibleSetOnly() {
        assertThat(courseAccess.library(member.getId(), org.getId()).catalog())
                .extracting("courseId").contains(courseId);

        visibilityService.update(courseId,
                new UpdateCourseVisibilityRequest("MIN_TIER", "FOUNDER_SUCCESS", null), admin.getId());

        assertThat(courseAccess.library(member.getId(), org.getId()).catalog())
                .extracting("courseId").doesNotContain(courseId);
    }

    /* -------------------------------------------------- Suggest → Accept §7b */

    @Test
    void anOpenSuggestionBecomesAnEnrollmentOnAccept_andIsStamped() {
        PillarRef pillar = pillar("Focus & Flow");
        UUID submissionId = suggestion(pillar.pillarId(), pillar.pipelineId());

        EffectiveCourse suggested = only(courseAccess.effectiveCoursesOf(member.getId(), org.getId()));
        assertThat(suggested.status()).isEqualTo(EffectiveCourseStatus.SUGGESTED);
        assertThat(suggested.source()).isEqualTo(EnrollmentSource.AI_SUGGESTED);
        assertThat(suggested.reason()).isEqualTo("Focus & Flow");
        assertThat(suggested.materialized()).isFalse();

        MemberCourseView accepted = courseAccess.accept(member.getId(), org.getId(), courseId);

        assertThat(accepted.status()).isEqualTo(EffectiveCourseStatus.ASSIGNED);
        assertThat(accepted.source()).isEqualTo(EnrollmentSource.AI_SUGGESTED);
        assertThat(accepted.materialized()).isTrue();
        assertThat(acceptedAt(submissionId)).isNotNull();

        // Accepted suggestions leave the open list — one card, once.
        assertThat(only(courseAccess.effectiveCoursesOf(member.getId(), org.getId())).status())
                .isEqualTo(EffectiveCourseStatus.ASSIGNED);
    }

    @Test
    void acceptRefusesADraftCourse_eventhoughSuggestModeNeverCheckedTheState() {
        // Suggest mode deliberately does not ask whether the course is PUBLISHED
        // at decision time; Accept is where that check has to live.
        PillarRef pillar = pillar("Focus & Flow");
        suggestion(pillar.pillarId(), pillar.pipelineId());
        draft(courseId);

        assertThatThrownBy(() -> courseAccess.accept(member.getId(), org.getId(), courseId))
                .isInstanceOf(BadRequestException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM enrollment WHERE user_id = :u AND course_id = :c",
                new MapSqlParameterSource("u", member.getId()).addValue("c", courseId), Integer.class))
                .isZero();
    }

    @Test
    void assignRefusesADraftCourseEvenWhenItIsVisible() {
        draft(courseId);

        assertThatThrownBy(() -> orgCourses.assign(org.getId(),
                new AssignCourseRequest(courseId, AssignCourseRequest.AUDIENCE_ORG, null, false, null),
                admin.getId()))
                .isInstanceOf(BadRequestException.class);
        assertThat(orgCourses.list(org.getId())).isEmpty();
    }

    @Test
    void narrowingVisibilityHidesAnUnopenedRuleButKeepsAnOpenedOne() {
        User opener = user("wiring-opener", UserRole.MEMBER);
        orgCourses.assign(org.getId(),
                new AssignCourseRequest(courseId, AssignCourseRequest.AUDIENCE_ORG, null, false, null),
                admin.getId());
        spine.ensureEnrollment(opener.getId(), courseId);
        jdbc.update("UPDATE enrollment SET progress_pct = 40 WHERE user_id = :u",
                new MapSqlParameterSource("u", opener.getId()));

        visibilityService.update(courseId,
                new UpdateCourseVisibilityRequest("ORG_LIST", null, List.of()), admin.getId());

        // The claim is gone for whoever never took it up...
        assertThat(courseAccess.effectiveCoursesOf(member.getId(), org.getId())).isEmpty();
        assertThat(spine.directCourses(member.getId())).isEmpty();
        // ...and the downgrade policy keeps the progress of whoever did.
        assertThat(only(courseAccess.effectiveCoursesOf(opener.getId(), org.getId())).progressPct())
                .isEqualTo(40);
    }

    /**
     * {@code enrollment.progress_pct} is only ever written by a learner's own
     * completion, so replacing a course's lessons used to leave every enrolment
     * frozen at a percentage nothing on screen agreed with — a member's journey
     * and player header read "60% complete" over a sidebar with nothing ticked.
     * The percent is now counted against the course's CURRENT lessons at read
     * time ({@code CourseProgressSql}), on both surfaces.
     */
    @Test
    void progressCountsTheCoursesCurrentLessons_notTheCachedPercent() {
        orgCourses.assign(org.getId(),
                new AssignCourseRequest(courseId, AssignCourseRequest.AUDIENCE_ORG, null, false, null),
                admin.getId());
        spine.ensureEnrollment(member.getId(), courseId);
        jdbc.update("UPDATE enrollment SET progress_pct = 60 WHERE user_id = :u",
                new MapSqlParameterSource("u", member.getId()));

        // The author republishes the course with a different lesson set; the
        // completions that made it 60% cascaded away with the rows they pointed at.
        List<UUID> lessons = lessons(4);

        assertThat(only(courseAccess.effectiveCoursesOf(member.getId(), org.getId())).progressPct())
                .isZero();
        assertThat(spine.directCourses(member.getId()).get(0).progressPct()).isZero();

        completeLesson(lessons.get(0));

        assertThat(only(courseAccess.effectiveCoursesOf(member.getId(), org.getId())).progressPct())
                .isEqualTo(25);
        assertThat(spine.directCourses(member.getId()).get(0).progressPct()).isEqualTo(25);
    }

    @Test
    void anExcludedMemberCannotSelfEnrol() {
        orgCourses.removeForMember(org.getId(), member.getId(), courseId, "not relevant",
                admin.getId());
        com.bvisionry.testsupport.TestAuthentication.authenticate(member);

        assertThatThrownBy(() -> enrollmentService.enroll(slug))
                .isInstanceOf(BadRequestException.class);
        assertThat(courseAccess.effectiveCoursesOf(member.getId(), org.getId())).isEmpty();
    }

    @Test
    void acceptRefusesACourseNobodyOfferedTheMember() {
        assertThatThrownBy(() -> courseAccess.accept(member.getId(), org.getId(), courseId))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void acceptRefusesACourseTheOrgCanNoLongerSee() {
        orgCourses.assign(org.getId(),
                new AssignCourseRequest(courseId, AssignCourseRequest.AUDIENCE_ORG, null, false, null),
                admin.getId());
        visibilityService.update(courseId,
                new UpdateCourseVisibilityRequest("ORG_LIST", null, List.of()), admin.getId());

        assertThatThrownBy(() -> courseAccess.accept(member.getId(), org.getId(), courseId))
                .isInstanceOf(BadRequestException.class);
    }

    /* ----------------------------------------------------- org Courses tab */

    @Test
    void theCoursesTabCountsRuleCoverageMinusExclusions() {
        User second = user("wiring-second", UserRole.MEMBER);
        orgCourses.assign(org.getId(),
                new AssignCourseRequest(courseId, AssignCourseRequest.AUDIENCE_ORG, null, true, null),
                admin.getId());

        // admin + member + second = 3 covered.
        OrgCourseRow row = only(orgCourses.list(org.getId()));
        assertThat(row.source()).isEqualTo(EnrollmentSource.ORG_RULE);
        assertThat(row.learners()).isEqualTo(3);
        assertThat(row.completionPct()).isZero();
        assertThat(row.required()).isTrue();
        assertThat(row.visible()).isTrue();
        assertThat(row.assignedAt()).isNotNull();

        orgCourses.removeForMember(org.getId(), second.getId(), courseId, null, admin.getId());
        OrgCourseRow afterOptOut = only(orgCourses.list(org.getId()));
        assertThat(afterOptOut.learners()).isEqualTo(2);
        assertThat(afterOptOut.audience()).contains("opted out");

        // One member finishes: 1 of 2 on the RULE row. Their materialized
        // enrollment also shows as its own row (a different thing to remove),
        // which is why this asserts on the rule rather than on the only row.
        complete(member.getId());
        assertThat(orgCourses.list(org.getId()))
                .filteredOn(r -> r.source() == EnrollmentSource.ORG_RULE)
                .singleElement()
                .satisfies(r -> assertThat(r.completionPct()).isEqualTo(50));
    }

    @Test
    void aDirectAssignmentIsItsOwnRowBesideTheRule() {
        orgCourses.assign(org.getId(),
                new AssignCourseRequest(courseId, AssignCourseRequest.AUDIENCE_ORG, null, false, null),
                admin.getId());
        orgCourses.assign(org.getId(), new AssignCourseRequest(courseId,
                AssignCourseRequest.AUDIENCE_MEMBERS, List.of(member.getId()), true, null), admin.getId());

        // Two rows, because they are two different things to remove.
        List<OrgCourseRow> rows = orgCourses.list(org.getId());
        assertThat(rows).hasSize(2)
                .extracting(OrgCourseRow::source)
                .containsExactlyInAnyOrder(EnrollmentSource.ORG_RULE, EnrollmentSource.DIRECT);
        assertThat(rows).filteredOn(r -> r.source() == EnrollmentSource.DIRECT)
                .singleElement()
                .satisfies(r -> assertThat(r.audience()).isEqualTo("1 selected member"));
    }

    @Test
    void assignRefusesAMemberOfAnotherOrganization() {
        Organization other = new Organization();
        other.setName("Foreign Org");
        other.setSubscriptionTier(SubscriptionTier.STARTER);
        other.setActive(true);
        other = orgs.saveAndFlush(other);
        User outsider = new User();
        outsider.setEmail("outsider-" + UUID.randomUUID() + "@bvisionry.invalid");
        outsider.setName("Outsider");
        outsider.setRole(UserRole.MEMBER);
        outsider.setStatus(UserStatus.ACTIVE);
        outsider.setOrganization(other);
        users.saveAndFlush(outsider);

        assertThatThrownBy(() -> orgCourses.assign(org.getId(), new AssignCourseRequest(courseId,
                        AssignCourseRequest.AUDIENCE_MEMBERS, List.of(outsider.getId()), false, null),
                admin.getId()))
                .isInstanceOf(BadRequestException.class);
    }

    /* ------------------------------------------------------- source stamping */

    @Test
    void selfEnrolmentIsTheDefaultSource_matchingTheV168Backfill() {
        // The migration labels every pre-existing row SELF unless the ledger says
        // otherwise; the column default has to agree or new rows drift from old.
        jdbc.update("INSERT INTO enrollment (user_id, course_id) VALUES (:u, :c)",
                new MapSqlParameterSource("u", member.getId()).addValue("c", courseId));

        assertThat(only(courseAccess.effectiveCoursesOf(member.getId(), org.getId())).source())
                .isEqualTo(EnrollmentSource.SELF);
    }

    /* ---------------------------------------------------------------- helpers */

    private static <T> T only(List<T> rows) {
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private User user(String prefix, UserRole role) {
        User u = new User();
        u.setEmail(prefix + "-" + UUID.randomUUID() + "@bvisionry.invalid");
        u.setName("Test " + prefix);
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setOrganization(org);
        return users.saveAndFlush(u);
    }

    private UUID course(String title, String slug) {
        return jdbc.queryForObject("""
                INSERT INTO course (org_id, slug, title, state)
                VALUES (:orgId, :slug, :title, 'PUBLISHED') RETURNING id
                """,
                new MapSqlParameterSource("orgId", org.getId()).addValue("slug", slug)
                        .addValue("title", title), UUID.class);
    }

    /** A section of {@code n} lessons on the seeded course. */
    private List<UUID> lessons(int n) {
        UUID sectionId = UUID.randomUUID();
        jdbc.update("INSERT INTO section (id, org_id, course_id, title) VALUES (:s, :o, :c, 'Module 1')",
                new MapSqlParameterSource("s", sectionId).addValue("o", org.getId())
                        .addValue("c", courseId));
        return IntStream.rangeClosed(1, n).mapToObj(i -> {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO content (id, org_id, section_id, title, sequence)
                    VALUES (:id, :o, :s, :t, :q)
                    """,
                    new MapSqlParameterSource("id", id).addValue("o", org.getId())
                            .addValue("s", sectionId).addValue("t", "Lesson " + i).addValue("q", i));
            return id;
        }).toList();
    }

    /** A self-enrolment as the endpoint writes it: SELF source, ACTIVE, no extras. */
    private void selfEnroll(UUID userId) {
        jdbc.update("INSERT INTO enrollment (user_id, course_id) VALUES (:u, :c)",
                new MapSqlParameterSource("u", userId).addValue("c", courseId));
    }

    private void completeLesson(UUID contentId) {
        jdbc.update("""
                INSERT INTO content_progress (enrollment_id, content_id, completed, completed_at)
                SELECT e.id, :content, true, now() FROM enrollment e
                 WHERE e.user_id = :u AND e.course_id = :c
                """,
                new MapSqlParameterSource("content", contentId)
                        .addValue("u", member.getId()).addValue("c", courseId));
    }

    private record PillarRef(UUID pipelineId, UUID pillarId) {}

    private PillarRef pillar(String name) {
        UUID pipelineId = jdbc.queryForObject("""
                INSERT INTO pipelines (name, status) VALUES (:name, 'DRAFT') RETURNING id
                """, new MapSqlParameterSource("name", "Wiring pipeline " + UUID.randomUUID()), UUID.class);
        UUID pillarId = jdbc.queryForObject("""
                INSERT INTO pillars (pipeline_id, name, display_order) VALUES (:p, :n, 0) RETURNING id
                """,
                new MapSqlParameterSource("p", pipelineId).addValue("n", name), UUID.class);
        return new PillarRef(pipelineId, pillarId);
    }

    /** An open Suggest-mode ledger row, written the way AutoEnrolmentService writes it. */
    private UUID suggestion(UUID pillarId, UUID pipelineId) {
        UUID assignmentId = jdbc.queryForObject("""
                INSERT INTO assignments (pipeline_id, organization_id, assigned_by, user_id)
                VALUES (:pipeline, :org, :admin, :member) RETURNING id
                """,
                new MapSqlParameterSource("pipeline", pipelineId).addValue("org", org.getId())
                        .addValue("admin", admin.getId()).addValue("member", member.getId()), UUID.class);
        UUID submissionId = jdbc.queryForObject("""
                INSERT INTO submissions (assignment_id, user_id, status)
                VALUES (:a, :u, 'EVALUATED') RETURNING id
                """,
                new MapSqlParameterSource("a", assignmentId).addValue("u", member.getId()), UUID.class);
        jdbc.update("""
                INSERT INTO auto_enrolments (user_id, course_id, submission_id, pillar_id,
                                             band_position, outcome)
                VALUES (:u, :c, :s, :p, 0, 'SUGGESTED')
                """,
                new MapSqlParameterSource("u", member.getId()).addValue("c", courseId)
                        .addValue("s", submissionId).addValue("p", pillarId));
        return submissionId;
    }

    private Instant acceptedAt(UUID submissionId) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT accepted_at FROM auto_enrolments WHERE submission_id = :s",
                new MapSqlParameterSource("s", submissionId), Timestamp.class);
        return ts == null ? null : ts.toInstant();
    }

    /** Pull a course out of the catalog without touching its visibility. */
    private void draft(UUID id) {
        jdbc.update("UPDATE course SET state = 'DRAFT' WHERE id = :id",
                new MapSqlParameterSource("id", id));
    }

    private void complete(UUID userId) {
        jdbc.update("""
                INSERT INTO enrollment (user_id, course_id, status, completed_at, progress_pct)
                VALUES (:u, :c, 'COMPLETED', NOW(), 100)
                ON CONFLICT ON CONSTRAINT uq_enrollment_user_course
                DO UPDATE SET status = 'COMPLETED', completed_at = NOW(), progress_pct = 100
                """,
                new MapSqlParameterSource("u", userId).addValue("c", courseId));
    }
}
