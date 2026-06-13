package com.bvisionry.organization;

/**
 * Centralized action-type strings for audit_logs entries that originate from
 * organization-related operations. Use constants instead of string literals
 * at call sites so a typo can't silently split the audit feed.
 */
public final class OrgAuditActions {
    private OrgAuditActions() {}

    public static final String ORGANIZATION_CREATED     = "ORGANIZATION_CREATED";
    public static final String ORGANIZATION_UPDATED     = "ORGANIZATION_UPDATED";
    public static final String ORGANIZATION_SUSPENDED   = "ORGANIZATION_SUSPENDED";
    public static final String ORGANIZATION_REACTIVATED = "ORGANIZATION_REACTIVATED";
    public static final String ORGANIZATION_DELETED     = "ORGANIZATION_DELETED";
    public static final String TIER_CHANGE              = "TIER_CHANGE";

    public static final String TRIAL_STARTED               = "TRIAL_STARTED";
    public static final String TRIAL_EXTENDED              = "TRIAL_EXTENDED";
    public static final String TRIAL_ENDED_EARLY           = "TRIAL_ENDED_EARLY";
    public static final String TRIAL_EXPIRED               = "TRIAL_EXPIRED";
    /** Recorded when the trial-ending-soon heads-up email is dispatched, used as an idempotency guard. */
    public static final String TRIAL_ENDING_SOON_NOTIFIED  = "TRIAL_ENDING_SOON_NOTIFIED";

    public static final String MEMBER_INVITED           = "MEMBER_INVITED";
    public static final String MEMBER_ROLE_CHANGED      = "MEMBER_ROLE_CHANGED";
    public static final String MEMBER_STATUS_CHANGED    = "MEMBER_STATUS_CHANGED";
    public static final String MEMBER_PROFILE_UPDATED   = "MEMBER_PROFILE_UPDATED";
    public static final String MEMBER_MOVED             = "MEMBER_MOVED";
    public static final String MEMBER_REMOVED           = "MEMBER_REMOVED";
    /** Platform-level role change (promote to / demote from SUPER_ADMIN) via /api/users. */
    public static final String USER_ROLE_CHANGED        = "USER_ROLE_CHANGED";
    public static final String JOIN_LINK_USED           = "JOIN_LINK_USED";
    public static final String CLEAR_RESPONSES          = "CLEAR_RESPONSES";

    public static final String ENTITY_JOIN_LINK   = "JoinLink";

    /** Recorded when a SUPER_ADMIN tunes the attention rule thresholds. */
    public static final String ATTENTION_THRESHOLDS_UPDATED = "ATTENTION_THRESHOLDS_UPDATED";

    // --- Assessment & survey lifecycle (drives the org Activity feed) -------
    public static final String ASSESSMENT_ASSIGNED       = "ASSESSMENT_ASSIGNED";
    public static final String AUTO_ASSIGN_RULE_CREATED  = "AUTO_ASSIGN_RULE_CREATED";
    public static final String AUTO_ASSIGN_RULE_UPDATED  = "AUTO_ASSIGN_RULE_UPDATED";
    public static final String AUTO_ASSIGN_RULE_DELETED  = "AUTO_ASSIGN_RULE_DELETED";
    public static final String ASSESSMENT_SUBMITTED      = "ASSESSMENT_SUBMITTED";
    public static final String ASSESSMENT_EVALUATED      = "ASSESSMENT_EVALUATED";
    public static final String ASSESSMENT_PILLARS_UNLOCKED = "ASSESSMENT_PILLARS_UNLOCKED";
    public static final String ASSESSMENT_PILLARS_RELOCKED = "ASSESSMENT_PILLARS_RELOCKED";
    public static final String ASSESSMENT_REEVALUATED      = "ASSESSMENT_REEVALUATED";
    public static final String ASSESSMENT_CHECK_IN_STARTED = "ASSESSMENT_CHECK_IN_STARTED";
    public static final String SURVEY_RESPONSE_SUBMITTED = "SURVEY_RESPONSE_SUBMITTED";

    /** Member on a Free-tier org clicked Request Upgrade. */
    public static final String UPGRADE_REQUESTED                 = "UPGRADE_REQUESTED";
    public static final String UPGRADE_PROMPT_UPDATED            = "UPGRADE_PROMPT_UPDATED";
    public static final String UPGRADE_PROMPT_RESET              = "UPGRADE_PROMPT_RESET";
    public static final String UPGRADE_REQUEST_RECIPIENTS_UPDATED = "UPGRADE_REQUEST_RECIPIENTS_UPDATED";

    public static final String ENTITY_ORGANIZATION = "Organization";
    public static final String ENTITY_PLATFORM     = "Platform";
    public static final String ENTITY_USER         = "User";
    public static final String ENTITY_SUBMISSION   = "Submission";
    public static final String ENTITY_SURVEY       = "Survey";
}
