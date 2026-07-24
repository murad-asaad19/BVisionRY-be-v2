package com.bvisionry.notification;

import com.bvisionry.notification.entity.EmailTemplateKey;
import com.bvisionry.notification.transport.MailAttachment;
import com.bvisionry.notification.transport.MailTransport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final MailTransport mailTransport;
    private final EmailTemplateRenderer templateRenderer;

    public void sendInvitationEmail(String toEmail, String organizationName, String acceptUrl, Instant expiresAt, String inviterName) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("organizationName", organizationName);
        vars.put("acceptUrl", acceptUrl);
        vars.put("expiresAt", expiresAt.toString());
        vars.put("inviterName", inviterName);

        send(toEmail, EmailTemplateKey.INVITATION, vars);
    }

    /**
     * Fire-and-forget variant used by the bulk-invite flow. A 50-recipient invite
     * with a slow Resend endpoint would otherwise hold row locks for the duration
     * of the surrounding {@code @Transactional} method. Failures are logged and
     * swallowed so one bad address doesn't poison the rest of the batch.
     */
    @Async("emailExecutor")
    public void sendInvitationEmailAsync(String toEmail, String organizationName, String acceptUrl,
                                          Instant expiresAt, String inviterName) {
        try {
            sendInvitationEmail(toEmail, organizationName, acceptUrl, expiresAt, inviterName);
        } catch (Exception e) {
            log.warn("Async invitation email to {} failed: {}", toEmail, e.getMessage());
        }
    }

    /**
     * Send the password-reset link. {@code resetUrl} embeds the single-use
     * token; {@code expiresAt} tells the recipient how long the link is valid.
     */
    public void sendPasswordResetEmail(String toEmail, String resetUrl, Instant expiresAt) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("resetUrl", resetUrl);
        vars.put("expiresAt", expiresAt.toString());

        send(toEmail, EmailTemplateKey.PASSWORD_RESET, vars);
    }

    /**
     * Fire-and-forget variant used by the forgot-password flow: the endpoint
     * must answer in constant time whether or not the account exists, so SMTP
     * latency (or failure) can never leak into the response.
     */
    @Async("emailExecutor")
    public void sendPasswordResetEmailAsync(String toEmail, String resetUrl, Instant expiresAt) {
        try {
            sendPasswordResetEmail(toEmail, resetUrl, expiresAt);
        } catch (Exception e) {
            log.warn("Async password-reset email to {} failed: {}", toEmail, e.getMessage());
        }
    }

    /**
     * Send notification when a new assessment is assigned.
     */
    public void sendAssessmentAssigned(String email, String memberName,
                                        String pipelineName, Instant deadline,
                                        String assessmentUrl) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("memberName", memberName);
        vars.put("pipelineName", pipelineName);
        vars.put("deadline", deadline);
        vars.put("assessmentUrl", assessmentUrl);

        send(email, EmailTemplateKey.ASSESSMENT_ASSIGNED, vars);
    }

    /**
     * Fire-and-forget variant used by the assignment flow. Email delivery runs on
     * the async executor so a slow/unreachable SMTP server cannot block the HTTP
     * response. Failures are logged, not surfaced to the caller.
     */
    @Async("emailExecutor")
    public void sendAssessmentAssignedAsync(String email, String memberName,
                                             String pipelineName, Instant deadline,
                                             String assessmentUrl) {
        try {
            sendAssessmentAssigned(email, memberName, pipelineName, deadline, assessmentUrl);
        } catch (Exception e) {
            log.warn("Async assignment email to {} failed: {}", email, e.getMessage());
        }
    }

    /**
     * Send reminder for incomplete assessment.
     */
    public void sendAssessmentReminder(String email, String memberName,
                                        String pipelineName, Instant deadline, String assessmentUrl) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("memberName", memberName);
        vars.put("pipelineName", pipelineName);
        vars.put("deadline", deadline);
        vars.put("assessmentUrl", assessmentUrl);

        send(email, EmailTemplateKey.ASSESSMENT_REMINDER, vars);
    }

    /**
     * Send notification when assessment results are ready.
     *
     * postCompletionUrl / postCompletionLabel are the pipeline's configured
     * follow-up link (typically a survey). Both may be null when the pipeline
     * has no post-completion action configured.
     */
    public void sendResultsReady(String email, String memberName, String pipelineName,
                                  String resultsUrl,
                                  String postCompletionUrl, String postCompletionLabel) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("memberName", memberName);
        vars.put("pipelineName", pipelineName);
        vars.put("resultsUrl", resultsUrl);
        vars.put("postCompletionUrl", postCompletionUrl);
        vars.put("postCompletionLabel", postCompletionLabel);

        send(email, EmailTemplateKey.RESULTS_READY, vars);
    }

    /**
     * Send the dedicated post-assessment survey invitation. Fired alongside
     * RESULTS_READY when the pipeline has a published survey paired to it,
     * so the survey CTA gets its own first-class, admin-editable email.
     */
    public void sendPostAssessmentSurveyInvite(String email, String memberName,
                                                 String pipelineName, String surveyName,
                                                 String surveyUrl, String resultsUrl) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("memberName", memberName);
        vars.put("pipelineName", pipelineName);
        vars.put("surveyName", surveyName);
        vars.put("surveyUrl", surveyUrl);
        vars.put("resultsUrl", resultsUrl);

        send(email, EmailTemplateKey.POST_ASSESSMENT_SURVEY_INVITE, vars);
    }

    /**
     * Send the "gift assessment" email to a survey respondent. Fired when a
     * survey configured with a gifted public assessment is completed through its
     * public link and the respondent left an email. {@code respondentName} may
     * be blank (the survey name field is optional).
     */
    public void sendSurveyGiftAssessment(String email, String respondentName,
                                          String surveyName, String assessmentTitle,
                                          String assessmentUrl) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("respondentName", respondentName == null ? "" : respondentName);
        vars.put("surveyName", surveyName);
        vars.put("assessmentTitle", assessmentTitle);
        vars.put("assessmentUrl", assessmentUrl);

        send(email, EmailTemplateKey.SURVEY_GIFT_ASSESSMENT, vars);
    }

    /**
     * Fire-and-forget variant used by the public survey submit flow. Delivery
     * runs on the async executor so a slow SMTP/Resend endpoint cannot block the
     * respondent's submission response. Failures are logged, not surfaced.
     */
    @Async("emailExecutor")
    public void sendSurveyGiftAssessmentAsync(String email, String respondentName,
                                               String surveyName, String assessmentTitle,
                                               String assessmentUrl) {
        try {
            sendSurveyGiftAssessment(email, respondentName, surveyName, assessmentTitle, assessmentUrl);
        } catch (Exception e) {
            log.warn("Async survey-gift-assessment email to {} failed: {}", email, e.getMessage());
        }
    }

    /**
     * Send the heads-up email when an org's Premium trial is approaching its end.
     * Recipients are typically ORG_ADMINs of the org (resolved by the caller).
     */
    public void sendTrialEndingSoon(String email, String organizationName, int daysLeft,
                                     Instant trialEndsAt, String dashboardUrl) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("organizationName", organizationName);
        vars.put("daysLeft", daysLeft);
        vars.put("trialEndsAt", trialEndsAt == null ? "" : trialEndsAt.toString());
        vars.put("dashboardUrl", dashboardUrl);

        send(email, EmailTemplateKey.TRIAL_ENDING_SOON, vars);
    }

    /**
     * Fire-and-forget variant for the heads-up email. Used by the trial scheduler so a
     * flaky SMTP server cannot roll back the audit-log idempotency write.
     */
    @Async("emailExecutor")
    public void sendTrialEndingSoonAsync(String email, String organizationName, int daysLeft,
                                          Instant trialEndsAt, String dashboardUrl) {
        try {
            sendTrialEndingSoon(email, organizationName, daysLeft, trialEndsAt, dashboardUrl);
        } catch (Exception e) {
            log.warn("Async trial-ending-soon email to {} failed: {}", email, e.getMessage());
        }
    }

    /**
     * Send the confirmation email when an org's Premium trial has just lapsed.
     */
    public void sendTrialExpired(String email, String organizationName,
                                  Instant expiredAt, String dashboardUrl) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("organizationName", organizationName);
        vars.put("expiredAt", expiredAt == null ? "" : expiredAt.toString());
        vars.put("dashboardUrl", dashboardUrl);

        send(email, EmailTemplateKey.TRIAL_EXPIRED, vars);
    }

    /**
     * Fire-and-forget variant. Called from {@code TrialService.expireLapsed()} so SMTP
     * failures don't roll back the trial-expiry transaction.
     */
    @Async("emailExecutor")
    public void sendTrialExpiredAsync(String email, String organizationName,
                                       Instant expiredAt, String dashboardUrl) {
        try {
            sendTrialExpired(email, organizationName, expiredAt, dashboardUrl);
        } catch (Exception e) {
            log.warn("Async trial-expired email to {} failed: {}", email, e.getMessage());
        }
    }

    /**
     * Send the platform-side notification when a Free-tier user clicks
     * "Request upgrade". Recipients are resolved upstream — typically every
     * SUPER_ADMIN, or a custom address list configured in platform settings.
     */
    public void sendUpgradeRequested(String email, String organizationName,
                                      String memberName, String memberEmail,
                                      String featureContext, String note,
                                      String dashboardUrl) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("organizationName", organizationName);
        vars.put("memberName", memberName);
        vars.put("memberEmail", memberEmail);
        vars.put("featureContext", featureContext);
        // Mustache hides the {{#note}}…{{/note}} block when the value is null
        // or empty, so falsy values produce a clean email rather than an empty
        // grey box.
        vars.put("note", note == null || note.isBlank() ? null : note);
        vars.put("dashboardUrl", dashboardUrl);

        send(email, EmailTemplateKey.UPGRADE_REQUESTED, vars);
    }

    /**
     * Fire-and-forget variant. Called from the upgrade-request flow so SMTP
     * latency or failure can't block the HTTP response or roll back the row.
     */
    @Async("emailExecutor")
    public void sendUpgradeRequestedAsync(String email, String organizationName,
                                           String memberName, String memberEmail,
                                           String featureContext, String note,
                                           String dashboardUrl) {
        try {
            sendUpgradeRequested(email, organizationName, memberName, memberEmail,
                    featureContext, note, dashboardUrl);
        } catch (Exception e) {
            log.warn("Async upgrade-requested email to {} failed: {}", email, e.getMessage());
        }
    }

    /**
     * Send the platform-side notification when a visitor submits the website
     * "Contact Us" form. Recipients are resolved upstream — a custom address
     * list configured in platform settings, falling back to every SUPER_ADMIN.
     */
    public void sendContactMessage(String toEmail, String senderName, String senderEmail,
                                    String company, String inquiry, String message) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("senderName", senderName);
        vars.put("senderEmail", senderEmail);
        // Mustache hides the {{#company}}…{{/company}} row when the value is
        // null or empty, so a blank company produces a clean card rather than
        // an empty row.
        vars.put("company", company == null || company.isBlank() ? null : company);
        vars.put("inquiry", inquiry);
        vars.put("message", message);

        // Reply-To = the visitor so admins can reply directly from their inbox.
        // From stays the configured platform address (no spoofing/open-relay risk).
        send(toEmail, EmailTemplateKey.CONTACT_US, vars, senderEmail);
    }

    /**
     * Fire-and-forget variant. Called from the contact-form flow so SMTP
     * latency or failure can't block the HTTP response.
     */
    @Async("emailExecutor")
    public void sendContactMessageAsync(String toEmail, String senderName, String senderEmail,
                                         String company, String inquiry, String message) {
        try {
            sendContactMessage(toEmail, senderName, senderEmail, company, inquiry, message);
        } catch (Exception e) {
            log.warn("Async contact-message email to {} failed: {}", toEmail, e.getMessage());
        }
    }

    /**
     * Send the platform-side notification when a visitor submits the website
     * Book-a-Demo / free-trial form. Recipients are resolved upstream — a
     * custom address list configured in platform settings, falling back to
     * every SUPER_ADMIN.
     */
    public void sendDemoRequest(String toEmail, String senderName, String senderEmail,
                                 String organization, String role, String programType,
                                 String cohortSize, String source, String message) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("senderName", senderName);
        vars.put("senderEmail", senderEmail);
        vars.put("organization", organization);
        vars.put("role", role);
        vars.put("programType", programType);
        // Mustache hides the {{#cohortSize}}…{{/cohortSize}} and
        // {{#source}}…{{/source}} rows when the value is null or empty, so
        // blank optional fields produce a clean card rather than empty rows.
        vars.put("cohortSize", cohortSize == null || cohortSize.isBlank() ? null : cohortSize);
        vars.put("source", source == null || source.isBlank() ? null : source);
        vars.put("message", message);

        // Reply-To = the visitor so admins can reply directly from their inbox.
        // From stays the configured platform address (no spoofing/open-relay risk).
        send(toEmail, EmailTemplateKey.DEMO_REQUEST, vars, senderEmail);
    }

    /**
     * Fire-and-forget variant. Called from the lead-capture flow so SMTP
     * latency or failure can't block the HTTP response or roll back the lead.
     */
    @Async("emailExecutor")
    public void sendDemoRequestAsync(String toEmail, String senderName, String senderEmail,
                                      String organization, String role, String programType,
                                      String cohortSize, String source, String message) {
        try {
            sendDemoRequest(toEmail, senderName, senderEmail, organization, role,
                    programType, cohortSize, source, message);
        } catch (Exception e) {
            log.warn("Async demo-request email to {} failed: {}", toEmail, e.getMessage());
        }
    }

    /**
     * Send the lead-magnet email to a website visitor who requested the research
     * PDF (the "science behind the 11 pillars" CTA on the Platform page). The PDF
     * is delivered as a real attachment — the caller loads the bytes from the
     * configured asset before invoking this.
     *
     * <p>Invoked from {@code LeadMagnetDispatcher}, which already runs on the
     * {@code emailExecutor}, so this method is intentionally synchronous (no
     * separate async variant — the dispatcher is the async boundary).
     */
    public void sendLeadMagnet(String email, byte[] pdf, String fileName) {
        Map<String, Object> vars = new HashMap<>();
        send(email, EmailTemplateKey.LEAD_MAGNET, vars, null,
                List.of(new MailAttachment(fileName, "application/pdf", pdf)));
    }

    /**
     * Send arbitrary pre-rendered content. Used by the admin "send test email"
     * flow so admins can preview their draft in their own inbox before saving.
     */
    public void sendRaw(String to, String subject, String htmlBody) {
        mailTransport.send(to, subject, htmlBody);
    }

    private void send(String to, EmailTemplateKey key, Map<String, Object> vars) {
        send(to, key, vars, null);
    }

    private void send(String to, EmailTemplateKey key, Map<String, Object> vars, String replyTo) {
        EmailTemplateRenderer.Rendered rendered = templateRenderer.render(key, vars);
        mailTransport.send(to, rendered.subject(), rendered.body(), replyTo);
    }

    private void send(String to, EmailTemplateKey key, Map<String, Object> vars, String replyTo,
                      List<MailAttachment> attachments) {
        EmailTemplateRenderer.Rendered rendered = templateRenderer.render(key, vars);
        mailTransport.send(to, rendered.subject(), rendered.body(), replyTo, attachments);
    }
}
