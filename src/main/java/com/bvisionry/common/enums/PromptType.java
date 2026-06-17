package com.bvisionry.common.enums;

public enum PromptType {
    SYSTEM_PROMPT,
    /** System prompt used only for public (QR-link) assessments, so the public
     *  flow can diverge from the internal {@link #SYSTEM_PROMPT} persona/tone. */
    PUBLIC_ASSESSMENT_SYSTEM_PROMPT,
    TEAM_INSIGHT,
    OVERALL_SUMMARY,
    FREE_TIER_SUMMARY
}
