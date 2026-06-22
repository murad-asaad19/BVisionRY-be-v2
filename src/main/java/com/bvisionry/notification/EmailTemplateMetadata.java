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
            case TRIAL_ENDING_SOON              -> "Trial Ending Soon";
            case TRIAL_EXPIRED                  -> "Trial Expired";
            case UPGRADE_REQUESTED              -> "Upgrade Requested";
            case CONTACT_US                     -> "Contact Message";
            case SURVEY_GIFT_ASSESSMENT         -> "Survey Gift Assessment";
            case LEAD_MAGNET                    -> "Lead Magnet (Science PDF)";
        };
    }

    public static String description(EmailTemplateKey key) {
        return switch (key) {
            case ASSESSMENT_ASSIGNED            -> "Sent to a member when a new assessment is assigned to them.";
            case ASSESSMENT_REMINDER            -> "Sent as a nudge when an assessment is still incomplete.";
            case RESULTS_READY                  -> "Sent once evaluation finishes and the member can view their results.";
            case POST_ASSESSMENT_SURVEY_INVITE  -> "Sent alongside the results email when the pipeline has a paired survey, inviting the member to share feedback.";
            case INVITATION                     -> "Sent to someone invited to join an organization on BVisionRY.";
            case TRIAL_ENDING_SOON              -> "Sent to org admins a few days before their Premium trial expires.";
            case TRIAL_EXPIRED                  -> "Sent to org admins once their Premium trial has ended.";
            case UPGRADE_REQUESTED              -> "Sent to platform admins when a member of a Free-tier org requests an upgrade to Premium.";
            case CONTACT_US                     -> "Sent to platform admins when someone submits the website contact form.";
            case SURVEY_GIFT_ASSESSMENT         -> "Sent to a respondent who completes a survey (via its public link) that is configured to gift a public assessment, with a link to take it.";
            case LEAD_MAGNET                    -> "Sent to a website visitor who requests the research PDF from the \"science behind the 11 pillars\" CTA on the Platform page. The PDF is delivered as an attachment.";
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
            case SURVEY_GIFT_ASSESSMENT -> List.of(
                    new TemplateVariable("respondentName",  "Name the respondent entered on the survey (may be empty)"),
                    new TemplateVariable("surveyName",      "Name of the survey they just completed"),
                    new TemplateVariable("assessmentTitle", "Title of the gifted public assessment"),
                    new TemplateVariable("assessmentUrl",   "Link the respondent opens to take the gifted assessment")
            );
            // No system variables — the PDF is an attachment and all copy is
            // admin-editable (fields.*).
            case LEAD_MAGNET -> List.of();
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
                    "assessmentUrl", urls.path("/my/assessments/sample")
            );
            case RESULTS_READY -> Map.of(
                    "memberName",          "Alex Johnson",
                    "pipelineName",        "Leadership Self-Assessment",
                    "resultsUrl",          urls.path("/my/assessments/sample/results"),
                    "postCompletionUrl",   "https://typeform.com/sample-feedback",
                    "postCompletionLabel", "Continue"
            );
            case POST_ASSESSMENT_SURVEY_INVITE -> Map.of(
                    "memberName",   "Alex Johnson",
                    "pipelineName", "Leadership Self-Assessment",
                    "surveyName",   "Post-Assessment Feedback",
                    "surveyUrl",    urls.path("/my/assessments/sample/post-completion-survey"),
                    "resultsUrl",   urls.path("/my/assessments/sample/results")
            );
            case INVITATION -> Map.of(
                    "inviterName",      "Jordan Lee",
                    "organizationName", "Acme Ventures",
                    "acceptUrl",        urls.path("/invite/sample-token"),
                    "expiresAt",        Instant.parse("2026-05-01T00:00:00Z").toString()
            );
            case TRIAL_ENDING_SOON -> Map.of(
                    "organizationName", "Acme Ventures",
                    "daysLeft",         3,
                    "trialEndsAt",      Instant.parse("2026-05-01T00:00:00Z").toString(),
                    "dashboardUrl",     urls.path("/admin/organizations/sample")
            );
            case TRIAL_EXPIRED -> Map.of(
                    "organizationName", "Acme Ventures",
                    "expiredAt",        Instant.parse("2026-04-25T00:00:00Z").toString(),
                    "dashboardUrl",     urls.path("/admin/organizations/sample")
            );
            case UPGRADE_REQUESTED -> Map.of(
                    "organizationName", "Acme Ventures",
                    "memberName",       "Alex Johnson",
                    "memberEmail",      "alex@acmeventures.com",
                    "featureContext",   "Org Insights",
                    "note",             "Our leadership team would really benefit from cohort comparisons before the next QBR.",
                    "dashboardUrl",     urls.path("/admin/organizations/sample")
            );
            case CONTACT_US -> Map.of(
                    "senderName",  "Jordan Rivera",
                    "senderEmail", "jordan@example.com",
                    "company",     "Acme Accelerator",
                    "inquiry",     "Partnership",
                    "message",     "We run a 12-week accelerator and would love to explore using the Founder Readiness Index with our next cohort."
            );
            case SURVEY_GIFT_ASSESSMENT -> Map.of(
                    "respondentName",  "Alex Johnson",
                    "surveyName",      "Founder Pulse Survey",
                    "assessmentTitle", "Founder Readiness Index",
                    "assessmentUrl",   urls.assessmentLink("sample-token")
            );
            case LEAD_MAGNET -> Map.of();
        };
    }
}
