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
            true);

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
