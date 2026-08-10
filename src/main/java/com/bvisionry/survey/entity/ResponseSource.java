package com.bvisionry.survey.entity;

public enum ResponseSource {
    PUBLIC_LINK,
    POST_ASSESSMENT,
    /** Verified pre-workshop intro survey — response is tied to (workshop, member). */
    WORKSHOP_INTRO,
    /** A cohort SURVEY journey task answered in-app — tied to (survey, member). */
    PROGRAM_TASK
}
