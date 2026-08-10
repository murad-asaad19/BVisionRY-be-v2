package com.bvisionry.programflow.dto;

/**
 * The org-admin onboarding checklist (spec §11): four booleans computed over
 * the BILLING FAMILY (root + sub-orgs). Dismissal is client-side persistence —
 * the backend keeps no dismiss state (the list is cheap to compute and the
 * booleans stay honest if the admin un-hides it).
 */
public record OnboardingChecklistResponse(
        boolean invitedMembers,
        boolean assignedFirstAssessment,
        boolean createdAndLaunchedCohort,
        boolean assignedCoach) {
}
