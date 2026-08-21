package com.bvisionry.common.programaccess;

/**
 * The single SQL source of truth for "may a MEMBER see this cohort right now?".
 *
 * <p>Spec §8, two-state since V183: members see LAUNCHED cohorts only; a DRAFT
 * (including a temporarily unlaunched cohort) is invisible. The JPQL twin is
 * {@code CohortRepository.findEnrolled} — JPQL cannot interpolate this
 * fragment, so that one restates the rule and names this class.
 *
 * <p>Composed (via {@code String.formatted}) by the member-facing raw-SQL
 * readers: {@code AnnouncementReadRepository} (recipientIds, isCohortMember,
 * memberFeed, isMemberVisible), {@code ComparisonReadRepository
 * .memberPairCohort} and {@code ProgramTaskSurveyRepository
 * .findEnrolledTaskSurvey}. Staff-facing reads deliberately do NOT use it —
 * admin boards, feeds and reviews work in any state. The one deliberate
 * exception is {@code CoachingReadRepository}'s roster Δ lateral: the number it
 * shows a coach is the MEMBER's growth anchor, so it has to land on the same
 * cohort {@code memberPairCohort} picks — without this fragment the roster and
 * the founder profile it links to would disagree for an unlaunched cohort.
 *
 * <p>Lives in {@code common} for the same reason as {@link ProgramAudience}:
 * the ArchUnit ratchet forbids feature→feature imports and {@code common} may
 * not import features, so this depends on the schema only.
 */
public final class CohortVisibility {

    private CohortVisibility() {}

    /**
     * The cohort aliased by {@code %1$s} in the composing query is visible to
     * members.
     */
    public static final String MEMBER_VISIBLE = "%1$s.status = 'LAUNCHED'";
}
