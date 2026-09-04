package com.bvisionry.notification.entity;

public enum EmailTemplateKey {
    ASSESSMENT_ASSIGNED,
    ASSESSMENT_REMINDER,
    RESULTS_READY,
    POST_ASSESSMENT_SURVEY_INVITE,
    INVITATION,
    PASSWORD_RESET,
    TRIAL_ENDING_SOON,
    TRIAL_EXPIRED,
    UPGRADE_REQUESTED,
    CONTACT_US,
    DEMO_REQUEST,
    SURVEY_GIFT_ASSESSMENT,
    LEAD_MAGNET,
    // Coaching calendar (coaching-sessions spec §7).
    COACHING_SESSION_BOOKED_MEMBER,
    COACHING_SESSION_BOOKED_COACH,
    COACHING_SESSION_CANCELLED,
    COACHING_SESSION_FEEDBACK,
    // Cohort-wide sessions and moves (coaching-sessions spec v2 §10). The
    // COACHING_SESSION_BOOKED_* pair stays 1:1-only: a group session is not
    // "your booking", and one template cannot say both.
    GROUP_SESSION_SCHEDULED_MEMBER,
    GROUP_SESSION_SCHEDULED_COACH,
    SESSION_RESCHEDULED
}
