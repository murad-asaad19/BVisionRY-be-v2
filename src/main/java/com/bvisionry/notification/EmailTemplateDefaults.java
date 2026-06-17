package com.bvisionry.notification;

import com.bvisionry.notification.entity.EmailTemplateKey;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Bundled default subject and body for each editable email template.
 * Admin-saved overrides live in the DB; this class is the fallback when
 * no override exists, and the source of truth when the admin clicks
 * "Reset to default".
 */
@Component
public class EmailTemplateDefaults {

    public String subject(EmailTemplateKey key) {
        return switch (key) {
            case ASSESSMENT_ASSIGNED            -> "New Assessment Assigned: {{pipelineName}}";
            case ASSESSMENT_REMINDER            -> "Reminder: Complete Your Assessment - {{pipelineName}}";
            case RESULTS_READY                  -> "Your Assessment Results Are Ready: {{pipelineName}}";
            case POST_ASSESSMENT_SURVEY_INVITE  -> "One last step for {{pipelineName}}: a quick survey";
            case INVITATION                     -> "You've been invited to join {{organizationName}}";
            case TRIAL_ENDING_SOON              -> "Your {{organizationName}} trial ends in {{daysLeft}} days";
            case TRIAL_EXPIRED                  -> "Your {{organizationName}} trial has ended";
            case UPGRADE_REQUESTED              -> "{{organizationName}} requested a Premium upgrade";
            case CONTACT_US                     -> "New contact message from {{senderName}}";
        };
    }

    public String body(EmailTemplateKey key) {
        String filename = switch (key) {
            case ASSESSMENT_ASSIGNED            -> "assessment-assigned.mustache";
            case ASSESSMENT_REMINDER            -> "assessment-reminder.mustache";
            case RESULTS_READY                  -> "results-ready.mustache";
            case POST_ASSESSMENT_SURVEY_INVITE  -> "post-assessment-survey-invite.mustache";
            case INVITATION                     -> "invitation.mustache";
            case TRIAL_ENDING_SOON              -> "trial-ending-soon.mustache";
            case TRIAL_EXPIRED                  -> "trial-expired.mustache";
            case UPGRADE_REQUESTED              -> "upgrade-requested.mustache";
            case CONTACT_US                     -> "contact-message.mustache";
        };
        try {
            ClassPathResource resource = new ClassPathResource("templates/email/" + filename);
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load default email template: " + filename, e);
        }
    }
}
