package com.bvisionry.notification.push;

import com.bvisionry.common.enums.UserRole;

import java.util.Arrays;
import java.util.List;

/**
 * Every push-notifiable event on the platform. {@code adminOnly} types are
 * only visible to — and only ever dispatched to — ORG_ADMIN / SUPER_ADMIN
 * users; the rest target the member the event is about.
 *
 * <p>Preferences are opt-out (see {@link NotificationOptOut}): a type is
 * enabled unless the user muted it, so adding a value here needs no backfill.
 * Label and description are served to the web client by
 * {@link NotificationController}, which keeps the preference UI free of a
 * duplicated type registry.
 */
public enum NotificationType {

    ASSESSMENT_ASSIGNED(
            "New assessment assigned",
            "When an assessment is assigned to you.",
            false),
    ASSESSMENT_REMINDER(
            "Assessment reminders",
            "When an admin nudges you about an assessment still in progress.",
            false),
    RESULTS_READY(
            "Assessment results ready",
            "When your submission has been evaluated and your results are available.",
            false),
    MEMBER_SUBMITTED(
            "Member completed an assessment",
            "When a member of your organization submits an assessment.",
            true),
    MEMBER_JOINED(
            "New member joined",
            "When a new member joins your organization.",
            true),
    COHORT_ENROLLED(
            "Added to a cohort",
            "When you are enrolled into a program cohort.",
            false),
    PROGRAM_MODULE_ASSIGNED(
            "Program module assigned",
            "When a program module is assigned to you.",
            false),
    PROGRAM_MODULE_UNLOCKED(
            "Program module unlocked",
            "When a scheduled module on your journey unlocks.",
            false),
    PROGRAM_TASK_DUE(
            "Program task due soon",
            "When a program task you haven't submitted is close to its due date.",
            false),
    PROGRAM_TASK_SUBMITTED(
            "Member completed a program task",
            "When a member of your organization submits a program task.",
            true),
    WORKSHOP_RESULTS_SHARED(
            "Workshop results shared",
            "When your team lead shares their results and your workshop tasks unlock.",
            false);

    private final String label;
    private final String description;
    private final boolean adminOnly;

    NotificationType(String label, String description, boolean adminOnly) {
        this.label = label;
        this.description = description;
        this.adminOnly = adminOnly;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    public boolean isAdminOnly() {
        return adminOnly;
    }

    public static boolean isAdminRole(UserRole role) {
        return role == UserRole.ORG_ADMIN || role == UserRole.SUPER_ADMIN;
    }

    /** The types a user of {@code role} can receive (and see in preferences). */
    public static List<NotificationType> visibleTo(UserRole role) {
        return Arrays.stream(values())
                .filter(type -> !type.adminOnly || isAdminRole(role))
                .toList();
    }
}
