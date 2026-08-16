package com.bvisionry.common.enums;

public enum PromptType {
    SYSTEM_PROMPT,
    /** System prompt used only for public (QR-link) assessments, so the public
     *  flow can diverge from the internal {@link #SYSTEM_PROMPT} persona/tone. */
    PUBLIC_ASSESSMENT_SYSTEM_PROMPT,
    TEAM_INSIGHT,
    OVERALL_SUMMARY,
    FREE_TIER_SUMMARY,
    /** System prompt for the Program Flow AI module composer (admin builder). */
    PROGRAM_COMPOSER,
    /** System prompt for the Program Flow AI coach hints (learner task player). */
    PROGRAM_COACH,
    /** System prompt for the admin AI-use detector (was a submission's free-text AI-written?). */
    AI_USE_DETECTION,
    /** System prompt for the Qualitative Shift Narrative job (redesign spec §6). */
    SHIFT_NARRATIVE,
    /** System prompt for the member-level growth summary (redesign spec §3). */
    MEMBER_GROWTH_SUMMARY,
    /** System prompt for the cohort-level growth summary (redesign spec §4). */
    COHORT_GROWTH_SUMMARY
}
