package com.bvisionry.notification.push;

import com.bvisionry.common.enums.UserRole;

import java.util.Arrays;
import java.util.List;

/**
 * Every push-notifiable event on the platform. {@code adminOnly} means NOT FOR
 * MEMBERS: the event is about someone else's activity, so it never reaches the
 * member it names. The rest target the member the event is about.
 *
 * <p><strong>{@code coachVisible} is a second, narrower distinction — not a
 * loosening of the first.</strong> The three SUBMISSION types are review work,
 * and redesign spec §2.2 makes the Review Queue "the notification target for
 * submissions": a coach who is never told a founder submitted has to poll the
 * queue to find out. So those three also reach COACH, while the rest of the
 * admin set (a member joining the org) is org administration and stays
 * admin-only. This flag decides only who may SEE and MUTE a type — WHICH
 * coaches a given event reaches is scoped per-founder by
 * {@link CoachReviewNotifier}; a coach never hears about a founder they do not
 * coach.
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
            true, true),
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
            true, true),
    WORKSHOP_RESULTS_SHARED(
            "Workshop results shared",
            "When your team lead shares their results and your workshop tasks unlock.",
            false),
    EXERCISE_ASSIGNED(
            "New exercise assigned",
            "When an exercise is assigned to you.",
            false),
    EXERCISE_FEEDBACK(
            "Exercise feedback",
            "When an admin comments on your exercise or completes their review.",
            false),
    EXERCISE_ACTIVITY(
            "Member exercise activity",
            "When a member of your organization submits an exercise or replies to your feedback.",
            true, true),
    ANNOUNCEMENT(
            "Cohort announcements",
            "When your coach or an admin broadcasts an announcement to your cohort.",
            false),
    INACTIVITY_NUDGE(
            "Stalled course nudges",
            "When a course you are enrolled on has seen no progress for a while.",
            false);

    private final String label;
    private final String description;
    private final boolean adminOnly;
    private final boolean coachVisible;

    NotificationType(String label, String description, boolean adminOnly) {
        this(label, description, adminOnly, false);
    }

    NotificationType(String label, String description, boolean adminOnly, boolean coachVisible) {
        this.label = label;
        this.description = description;
        this.adminOnly = adminOnly;
        this.coachVisible = coachVisible;
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

    /** Also dispatched to the coaches holding a grant on the member it is about. */
    public boolean isCoachVisible() {
        return coachVisible;
    }

    public static boolean isAdminRole(UserRole role) {
        return role == UserRole.ORG_ADMIN || role == UserRole.SUPER_ADMIN;
    }

    /**
     * Whether a user of {@code role} may receive this type — and therefore mute
     * it. The single predicate behind both the preferences list and the
     * toggle's guard: split them and a coach gets a switch the PUT rejects.
     */
    public boolean isVisibleTo(UserRole role) {
        return !adminOnly || isAdminRole(role) || (coachVisible && role == UserRole.COACH);
    }

    /** The types a user of {@code role} can receive (and see in preferences). */
    public static List<NotificationType> visibleTo(UserRole role) {
        return Arrays.stream(values())
                .filter(type -> type.isVisibleTo(role))
                .toList();
    }
}
