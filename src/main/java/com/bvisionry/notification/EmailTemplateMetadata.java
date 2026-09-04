package com.bvisionry.notification;

import com.bvisionry.config.FrontendUrls;
import com.bvisionry.notification.dto.EmailTemplateDto.TemplateVariable;
import com.bvisionry.notification.entity.EmailTemplateKey;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Human-facing metadata for each template: display name, purpose, the variables
 * the template understands, and sample values for preview. Centralized here so
 * the admin UI stays in sync with the Mustache variables the backend actually
 * populates when sending the real email.
 */
public final class EmailTemplateMetadata {

    private EmailTemplateMetadata() {}

    public static String displayName(EmailTemplateKey key) {
        return switch (key) {
            case ASSESSMENT_ASSIGNED            -> "Assessment Assigned";
            case ASSESSMENT_REMINDER            -> "Assessment Reminder";
            case RESULTS_READY                  -> "Results Ready";
            case POST_ASSESSMENT_SURVEY_INVITE  -> "Post-assessment Survey Invitation";
            case INVITATION                     -> "Organization Invitation";
            case PASSWORD_RESET                 -> "Password Reset";
            case TRIAL_ENDING_SOON              -> "Trial Ending Soon";
            case TRIAL_EXPIRED                  -> "Trial Expired";
            case UPGRADE_REQUESTED              -> "Upgrade Requested";
            case CONTACT_US                     -> "Contact Message";
            case DEMO_REQUEST                   -> "Demo Request";
            case SURVEY_GIFT_ASSESSMENT         -> "Survey Gift Assessment";
            case LEAD_MAGNET                    -> "Lead Magnet (Science PDF)";
            case COACHING_SESSION_BOOKED_MEMBER -> "Coaching Session Booked (Founder)";
            case COACHING_SESSION_BOOKED_COACH  -> "Coaching Session Booked (Coach)";
            case COACHING_SESSION_CANCELLED     -> "Coaching Session Cancelled";
            case COACHING_SESSION_FEEDBACK      -> "Coaching Session Feedback Request";
            case GROUP_SESSION_SCHEDULED_MEMBER -> "Group Session Scheduled (Founder)";
            case GROUP_SESSION_SCHEDULED_COACH  -> "Group Session Scheduled (Coach)";
            case SESSION_RESCHEDULED            -> "Session Rescheduled";
        };
    }

    public static String description(EmailTemplateKey key) {
        return switch (key) {
            case ASSESSMENT_ASSIGNED            -> "Sent to a member when a new assessment is assigned to them.";
            case ASSESSMENT_REMINDER            -> "Sent as a nudge when an assessment is still incomplete.";
            case RESULTS_READY                  -> "Sent once evaluation finishes and the member can view their results.";
            case POST_ASSESSMENT_SURVEY_INVITE  -> "Sent alongside the results email when the pipeline has a paired survey, inviting the member to share feedback.";
            case INVITATION                     -> "Sent to someone invited to join an organization on Bvisionry.";
            case PASSWORD_RESET                 -> "Sent when a user requests a password reset from the \"Forgot your password?\" link, with a single-use link to choose a new password.";
            case TRIAL_ENDING_SOON              -> "Sent to org admins a few days before their Premium trial expires.";
            case TRIAL_EXPIRED                  -> "Sent to org admins once their Premium trial has ended.";
            case UPGRADE_REQUESTED              -> "Sent to platform admins when a member of a Free-tier org requests an upgrade to Premium.";
            case CONTACT_US                     -> "Sent to platform admins when someone submits the website contact form.";
            case DEMO_REQUEST                   -> "Sent to platform admins when someone requests a free trial through the website Book-a-Demo form.";
            case SURVEY_GIFT_ASSESSMENT         -> "Sent to a respondent who completes a survey (via its public link) that is configured to gift a public assessment, with a link to take it.";
            case LEAD_MAGNET                    -> "Sent to a website visitor who requests the research PDF from the \"science behind the 11 pillars\" CTA on the Platform page. The PDF is delivered as an attachment.";
            case COACHING_SESSION_BOOKED_MEMBER -> "Sent to the founder when they book a 1:1 from a coaching-session task, with the session details and a calendar invite attached.";
            case COACHING_SESSION_BOOKED_COACH  -> "Sent to the coach when a founder books one of their slots, with a calendar invite attached.";
            case COACHING_SESSION_CANCELLED     -> "Sent to the OTHER party when a coaching session is cancelled — the founder when the coach cancels, the coach when the founder does.";
            case COACHING_SESSION_FEEDBACK      -> "Sent to the founder once the coach marks the session held with them present, when the task names a post-session survey.";
            case GROUP_SESSION_SCHEDULED_MEMBER -> "Sent to every founder in the cohort when a group-coaching or workshop session is dated — the whole cohort meets at once, so nobody books it. A calendar invite is attached.";
            case GROUP_SESSION_SCHEDULED_COACH  -> "Sent to the coach who will run a group-coaching or workshop session once it is dated, with the size of the room and a calendar invite.";
            case SESSION_RESCHEDULED            -> "Sent when a session MOVES rather than being cancelled and rebooked — to the coach when a founder moves their 1:1, and to the cohort (plus the coach, when an administrator did it) when a group session moves. Carries both the old and the new time, and a calendar invite for the new one.";
        };
    }

    public static List<TemplateVariable> variables(EmailTemplateKey key) {
        return switch (key) {
            case ASSESSMENT_ASSIGNED -> List.of(
                    new TemplateVariable("memberName",    "Name of the member receiving the assessment"),
                    new TemplateVariable("pipelineName",  "Name of the assigned pipeline"),
                    new TemplateVariable("deadline",      "Due date (may be empty if no deadline)"),
                    new TemplateVariable("assessmentUrl", "Direct link to start the assessment")
            );
            case ASSESSMENT_REMINDER -> List.of(
                    new TemplateVariable("memberName",    "Name of the member being reminded"),
                    new TemplateVariable("pipelineName",  "Name of the pipeline still to complete"),
                    new TemplateVariable("deadline",      "Due date (may be empty if no deadline)"),
                    new TemplateVariable("assessmentUrl", "Direct link to resume the assessment")
            );
            case RESULTS_READY -> List.of(
                    new TemplateVariable("memberName",          "Name of the member whose results are ready"),
                    new TemplateVariable("pipelineName",        "Name of the evaluated pipeline"),
                    new TemplateVariable("resultsUrl",          "Link to the results dashboard"),
                    new TemplateVariable("postCompletionUrl",   "Optional external follow-up link configured on the pipeline"),
                    new TemplateVariable("postCompletionLabel", "Button label for the external follow-up link")
            );
            case POST_ASSESSMENT_SURVEY_INVITE -> List.of(
                    new TemplateVariable("memberName",   "Name of the member invited to take the survey"),
                    new TemplateVariable("pipelineName", "Name of the assessment they just completed"),
                    new TemplateVariable("surveyName",   "Name of the survey paired to the pipeline"),
                    new TemplateVariable("surveyUrl",    "Authenticated link the member opens to take the survey"),
                    new TemplateVariable("resultsUrl",   "Link back to the member's assessment results")
            );
            case INVITATION -> List.of(
                    new TemplateVariable("inviterName",      "Name of the person sending the invite"),
                    new TemplateVariable("organizationName", "Organization being joined"),
                    new TemplateVariable("acceptUrl",        "Link the recipient clicks to accept"),
                    new TemplateVariable("expiresAt",        "When this invitation expires")
            );
            case PASSWORD_RESET -> List.of(
                    new TemplateVariable("resetUrl",  "Single-use link the recipient opens to choose a new password"),
                    new TemplateVariable("expiresAt", "When this reset link expires")
            );
            case TRIAL_ENDING_SOON -> List.of(
                    new TemplateVariable("organizationName", "Organization whose trial is ending"),
                    new TemplateVariable("daysLeft",         "Whole days remaining on the trial"),
                    new TemplateVariable("trialEndsAt",      "Timestamp when the trial expires"),
                    new TemplateVariable("dashboardUrl",     "Link to the org's admin dashboard")
            );
            case TRIAL_EXPIRED -> List.of(
                    new TemplateVariable("organizationName", "Organization whose trial just ended"),
                    new TemplateVariable("expiredAt",        "Timestamp when the trial expired"),
                    new TemplateVariable("dashboardUrl",     "Link to the org's admin dashboard")
            );
            case UPGRADE_REQUESTED -> List.of(
                    new TemplateVariable("organizationName", "Organization that wants to upgrade"),
                    new TemplateVariable("memberName",       "Member who clicked Request Upgrade"),
                    new TemplateVariable("memberEmail",      "Member's email address (for direct reply)"),
                    new TemplateVariable("featureContext",   "Which feature surface they were on when they asked (e.g. Insights)"),
                    new TemplateVariable("note",             "Optional message the member included with their request"),
                    new TemplateVariable("dashboardUrl",     "Link to the org's admin dashboard")
            );
            case CONTACT_US -> List.of(
                    new TemplateVariable("senderName",  "Name the visitor entered in the contact form"),
                    new TemplateVariable("senderEmail", "Visitor's email address (for direct reply)"),
                    new TemplateVariable("company",     "Visitor's company / organization (may be empty)"),
                    new TemplateVariable("inquiry",     "What the message is about (the selected topic)"),
                    new TemplateVariable("message",     "The message the visitor wrote")
            );
            case DEMO_REQUEST -> List.of(
                    new TemplateVariable("senderName",   "Name the visitor entered in the demo-request form"),
                    new TemplateVariable("senderEmail",  "Visitor's email address (for direct reply)"),
                    new TemplateVariable("organization", "Organization requesting the demo"),
                    new TemplateVariable("role",         "Visitor's role at the organization"),
                    new TemplateVariable("programType",  "Program type they selected in the form"),
                    new TemplateVariable("cohortSize",   "Cohort / team size bucket (may be empty)"),
                    new TemplateVariable("source",       "Website surface that submitted the lead (may be empty)"),
                    new TemplateVariable("message",      "The message the visitor wrote")
            );
            case SURVEY_GIFT_ASSESSMENT -> List.of(
                    new TemplateVariable("respondentName",  "Name the respondent entered on the survey (may be empty)"),
                    new TemplateVariable("surveyName",      "Name of the survey they just completed"),
                    new TemplateVariable("assessmentTitle", "Title of the gifted public assessment"),
                    new TemplateVariable("assessmentUrl",   "Link the respondent opens to take the gifted assessment")
            );
            // No system variables — the PDF is an attachment and all copy is
            // admin-editable (fields.*).
            case LEAD_MAGNET -> List.of();
            case COACHING_SESSION_BOOKED_MEMBER -> List.of(
                    new TemplateVariable("memberName",      "Name of the founder who booked"),
                    new TemplateVariable("coachName",       "Name of the coach they booked"),
                    new TemplateVariable("sessionDate",     "Session date, written out in the coach's time zone"),
                    new TemplateVariable("sessionTime",     "Start time with the zone named, e.g. 10:00 (Europe/Berlin)"),
                    new TemplateVariable("durationMinutes", "How long the session runs, in minutes"),
                    new TemplateVariable("taskName",        "Name of the coaching-session task on the journey"),
                    new TemplateVariable("cohortName",      "Cohort the task belongs to"),
                    new TemplateVariable("journeyUrl",      "Link back to the booking on the founder's journey"),
                    new TemplateVariable("meetingUrl",      "Video-call link, when the coach's calendar created one (may be empty)")
            );
            case COACHING_SESSION_BOOKED_COACH -> List.of(
                    new TemplateVariable("coachName",       "Name of the coach receiving the booking"),
                    new TemplateVariable("memberName",      "Name of the founder who booked"),
                    new TemplateVariable("sessionDate",     "Session date, written out in the coach's time zone"),
                    new TemplateVariable("sessionTime",     "Start time with the zone named, e.g. 10:00 (Europe/Berlin)"),
                    new TemplateVariable("durationMinutes", "How long the session runs, in minutes"),
                    new TemplateVariable("taskName",        "Name of the coaching-session task on the journey"),
                    new TemplateVariable("cohortName",      "Cohort the task belongs to"),
                    new TemplateVariable("sessionsUrl",     "Link to the coach's Sessions list"),
                    new TemplateVariable("meetingUrl",      "Video-call link, when the coach's calendar created one (may be empty)")
            );
            case COACHING_SESSION_CANCELLED -> List.of(
                    new TemplateVariable("recipientName",   "Name of the person being told"),
                    new TemplateVariable("otherPartyName",  "Name of the person who cancelled"),
                    new TemplateVariable("sessionDate",     "Date the session would have been held"),
                    new TemplateVariable("sessionTime",     "Start time with the zone named"),
                    new TemplateVariable("taskName",        "Name of the coaching-session task"),
                    new TemplateVariable("rebookUrl",       "Link to book again (the founder's booking screen)")
            );
            case COACHING_SESSION_FEEDBACK -> List.of(
                    new TemplateVariable("memberName",      "Name of the founder who attended"),
                    new TemplateVariable("coachName",       "Name of the coach they met"),
                    new TemplateVariable("taskName",        "Name of the coaching-session task"),
                    new TemplateVariable("surveyUrl",       "Authenticated link to the post-session survey")
            );
            case GROUP_SESSION_SCHEDULED_MEMBER -> List.of(
                    new TemplateVariable("memberName",      "Name of the founder being told"),
                    new TemplateVariable("coachName",       "Name of the coach running the session"),
                    new TemplateVariable("sessionDate",     "Session date, written out in the coach's time zone"),
                    new TemplateVariable("sessionTime",     "Start time with the zone named, e.g. 10:00 (Europe/Berlin)"),
                    new TemplateVariable("durationMinutes", "How long the session runs, in minutes"),
                    new TemplateVariable("taskName",        "Name of the session task on the journey"),
                    new TemplateVariable("cohortName",      "Cohort whose founders all attend"),
                    new TemplateVariable("journeyUrl",      "Link to the session on the founder's journey"),
                    new TemplateVariable("meetingUrl",      "Video-call link, when the coach's calendar created one (may be empty)")
            );
            case GROUP_SESSION_SCHEDULED_COACH -> List.of(
                    new TemplateVariable("coachName",       "Name of the coach running the session"),
                    new TemplateVariable("attendeeCount",   "How many founders are expected"),
                    new TemplateVariable("sessionDate",     "Session date, written out in the coach's time zone"),
                    new TemplateVariable("sessionTime",     "Start time with the zone named, e.g. 10:00 (Europe/Berlin)"),
                    new TemplateVariable("durationMinutes", "How long the session runs, in minutes"),
                    new TemplateVariable("taskName",        "Name of the session task on the journey"),
                    new TemplateVariable("cohortName",      "Cohort whose founders all attend"),
                    new TemplateVariable("sessionsUrl",     "Link to the coach's Sessions list"),
                    new TemplateVariable("meetingUrl",      "Video-call link, when the coach's calendar created one (may be empty)")
            );
            case SESSION_RESCHEDULED -> List.of(
                    new TemplateVariable("recipientName",   "Name of the person being told"),
                    new TemplateVariable("taskName",        "Name of the session task"),
                    new TemplateVariable("cohortName",      "Cohort the session belongs to"),
                    new TemplateVariable("previousDate",    "Date the session used to be on"),
                    new TemplateVariable("previousTime",    "Start time it used to have, with the zone named"),
                    new TemplateVariable("sessionDate",     "The new date"),
                    new TemplateVariable("sessionTime",     "The new start time, with the zone named"),
                    new TemplateVariable("durationMinutes", "How long the session runs, in minutes"),
                    new TemplateVariable("movedBy",         "Who moved it — the founder's name, the coach's name, or \"an administrator\""),
                    new TemplateVariable("meetingUrl",      "Video-call link, when the coach's calendar created one (may be empty)"),
                    new TemplateVariable("url",             "Link to the session — the founder's journey, or the coach's Sessions list")
            );
        };
    }

    /**
     * Sample values used by the preview endpoint and test-send flow so admins
     * can see rendered output without waiting for a real assessment to happen.
     *
     * <p>Link-valued samples are built through {@link FrontendUrls} — the single
     * owner of the frontend origin — so a preview/test send always points at the
     * real configured host ({@code https://bvisionry.com} in prod,
     * {@code http://localhost:3000} in dev) and can never drift to a stale,
     * hand-typed domain.
     */
    public static Map<String, Object> sampleValues(EmailTemplateKey key, FrontendUrls urls) {
        return switch (key) {
            case ASSESSMENT_ASSIGNED, ASSESSMENT_REMINDER -> Map.of(
                    "memberName",    "Alex Johnson",
                    "pipelineName",  "Leadership Self-Assessment",
                    "deadline",      Instant.parse("2026-05-15T00:00:00Z").toString(),
                    "assessmentUrl", urls.path("/app/assessments/sample")
            );
            case RESULTS_READY -> Map.of(
                    "memberName",          "Alex Johnson",
                    "pipelineName",        "Leadership Self-Assessment",
                    "resultsUrl",          urls.path("/app/assessments/sample/results"),
                    "postCompletionUrl",   "https://typeform.com/sample-feedback",
                    "postCompletionLabel", "Continue"
            );
            case POST_ASSESSMENT_SURVEY_INVITE -> Map.of(
                    "memberName",   "Alex Johnson",
                    "pipelineName", "Leadership Self-Assessment",
                    "surveyName",   "Post-Assessment Feedback",
                    "surveyUrl",    urls.path("/app/assessments/sample/post-completion-survey"),
                    "resultsUrl",   urls.path("/app/assessments/sample/results")
            );
            case INVITATION -> Map.of(
                    "inviterName",      "Jordan Lee",
                    "organizationName", "Acme Ventures",
                    "acceptUrl",        urls.path("/invitations/sample-token"),
                    "expiresAt",        Instant.parse("2026-05-01T00:00:00Z").toString()
            );
            case PASSWORD_RESET -> Map.of(
                    "resetUrl",  urls.path("/reset-password/sample-token"),
                    "expiresAt", Instant.parse("2026-05-01T00:00:00Z").toString()
            );
            case TRIAL_ENDING_SOON -> Map.of(
                    "organizationName", "Acme Ventures",
                    "daysLeft",         3,
                    "trialEndsAt",      Instant.parse("2026-05-01T00:00:00Z").toString(),
                    "dashboardUrl",     urls.path("/app/admin/organizations/sample")
            );
            case TRIAL_EXPIRED -> Map.of(
                    "organizationName", "Acme Ventures",
                    "expiredAt",        Instant.parse("2026-04-25T00:00:00Z").toString(),
                    "dashboardUrl",     urls.path("/app/admin/organizations/sample")
            );
            case UPGRADE_REQUESTED -> Map.of(
                    "organizationName", "Acme Ventures",
                    "memberName",       "Alex Johnson",
                    "memberEmail",      "alex@acmeventures.com",
                    "featureContext",   "Org Insights",
                    "note",             "Our leadership team would really benefit from cohort comparisons before the next QBR.",
                    "dashboardUrl",     urls.path("/app/admin/organizations/sample")
            );
            case CONTACT_US -> Map.of(
                    "senderName",  "Jordan Rivera",
                    "senderEmail", "jordan@example.com",
                    "company",     "Acme Accelerator",
                    "inquiry",     "Partnership",
                    "message",     "We run a 12-week accelerator and would love to explore using the Founder Readiness Index with our next cohort."
            );
            case DEMO_REQUEST -> Map.of(
                    "senderName",   "Jordan Rivera",
                    "senderEmail",  "jordan@example.com",
                    "organization", "Acme Accelerator",
                    "role",         "Program Director",
                    "programType",  "Accelerator",
                    "cohortSize",   "1-50",
                    "source",       "book-demo-modal",
                    "message",      "We'd love to see how the Founder Readiness Index fits our next cohort."
            );
            case SURVEY_GIFT_ASSESSMENT -> Map.of(
                    "respondentName",  "Alex Johnson",
                    "surveyName",      "Founder Pulse Survey",
                    "assessmentTitle", "Founder Readiness Index",
                    "assessmentUrl",   urls.assessmentLink("sample-token")
            );
            case LEAD_MAGNET -> Map.of();
            case COACHING_SESSION_BOOKED_MEMBER -> Map.of(
                    "memberName",      "Alex Johnson",
                    "coachName",       "Jordan Lee",
                    "sessionDate",     "Monday, 14 September 2026",
                    "sessionTime",     "10:00 (Europe/Berlin)",
                    "durationMinutes", 45,
                    "taskName",        "Coaching 1:1",
                    "cohortName",      "Spring Cohort",
                    "journeyUrl",      urls.path("/app/program/tasks/sample/session"),
                    "meetingUrl",      "https://meet.google.com/abc-defg-hij"
            );
            case COACHING_SESSION_BOOKED_COACH -> Map.of(
                    "coachName",       "Jordan Lee",
                    "memberName",      "Alex Johnson",
                    "sessionDate",     "Monday, 14 September 2026",
                    "sessionTime",     "10:00 (Europe/Berlin)",
                    "durationMinutes", 45,
                    "taskName",        "Coaching 1:1",
                    "cohortName",      "Spring Cohort",
                    "sessionsUrl",     urls.path("/app/team/sessions"),
                    "meetingUrl",      "https://meet.google.com/abc-defg-hij"
            );
            case COACHING_SESSION_CANCELLED -> Map.of(
                    "recipientName",  "Alex Johnson",
                    "otherPartyName", "Jordan Lee",
                    "sessionDate",    "Monday, 14 September 2026",
                    "sessionTime",    "10:00 (Europe/Berlin)",
                    "taskName",       "Coaching 1:1",
                    "rebookUrl",      urls.path("/app/program/tasks/sample/session")
            );
            case COACHING_SESSION_FEEDBACK -> Map.of(
                    "memberName", "Alex Johnson",
                    "coachName",  "Jordan Lee",
                    "taskName",   "Coaching 1:1",
                    "surveyUrl",  urls.path("/app/program/tasks/sample/survey")
            );
            case GROUP_SESSION_SCHEDULED_MEMBER -> Map.of(
                    "memberName",      "Alex Johnson",
                    "coachName",       "Jordan Lee",
                    "sessionDate",     "Monday, 14 September 2026",
                    "sessionTime",     "10:00 (Europe/Berlin)",
                    "durationMinutes", 90,
                    "taskName",        "Group coaching",
                    "cohortName",      "Spring Cohort",
                    "journeyUrl",      urls.path("/app/program/tasks/sample/session"),
                    "meetingUrl",      "https://meet.google.com/abc-defg-hij"
            );
            case GROUP_SESSION_SCHEDULED_COACH -> Map.of(
                    "coachName",       "Jordan Lee",
                    "attendeeCount",   12,
                    "sessionDate",     "Monday, 14 September 2026",
                    "sessionTime",     "10:00 (Europe/Berlin)",
                    "durationMinutes", 90,
                    "taskName",        "Group coaching",
                    "cohortName",      "Spring Cohort",
                    "sessionsUrl",     urls.path("/app/team/sessions"),
                    "meetingUrl",      "https://meet.google.com/abc-defg-hij"
            );
            case SESSION_RESCHEDULED -> Map.of(
                    "recipientName",   "Alex Johnson",
                    "taskName",        "Coaching 1:1",
                    "cohortName",      "Spring Cohort",
                    "previousDate",    "Monday, 14 September 2026",
                    "previousTime",    "10:00 (Europe/Berlin)",
                    "sessionDate",     "Thursday, 17 September 2026",
                    "sessionTime",     "14:00 (Europe/Berlin)",
                    "durationMinutes", 45,
                    "movedBy",         "Jordan Lee",
                    // No meetingUrl: this is the preview of a session whose coach
                    // has no connected calendar, which is the shape that hides the
                    // Join line — the two GROUP_SESSION_* previews show the other.
                    "url",             urls.path("/app/program/tasks/sample/session")
            );
        };
    }
}
