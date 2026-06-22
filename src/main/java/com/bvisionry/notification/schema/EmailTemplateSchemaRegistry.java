package com.bvisionry.notification.schema;

import com.bvisionry.notification.entity.EmailTemplateKey;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Source of truth for which pieces of each email template are admin-editable,
 * what form control renders them, what their defaults are, and which wizard
 * step they belong to. The mustache skeletons in templates/email/*.mustache
 * reference these by id as {{fields.<id>}}.
 *
 * <p>The {@code subject} field is present for every key: it is rendered outside
 * the HTML body but participates in the same field/value pipeline so admins edit
 * it in the wizard like any other piece of text.
 */
@Component
public class EmailTemplateSchemaRegistry {

    private static final List<String> NO_VARS = List.of();

    public List<EmailTemplateField> schemaFor(EmailTemplateKey key) {
        return switch (key) {
            case ASSESSMENT_ASSIGNED            -> assessmentAssigned();
            case ASSESSMENT_REMINDER            -> assessmentReminder();
            case RESULTS_READY                  -> resultsReady();
            case POST_ASSESSMENT_SURVEY_INVITE  -> postAssessmentSurveyInvite();
            case INVITATION                     -> invitation();
            case TRIAL_ENDING_SOON              -> trialEndingSoon();
            case TRIAL_EXPIRED                  -> trialExpired();
            case UPGRADE_REQUESTED              -> upgradeRequested();
            case CONTACT_US                     -> contactMessage();
            case SURVEY_GIFT_ASSESSMENT         -> surveyGiftAssessment();
            case LEAD_MAGNET                    -> leadMagnet();
        };
    }

    /**
     * Default values keyed by field id. The value type matches the field kind —
     * {@link String} for text fields, {@link List}{@code <String>} for LIST fields —
     * so callers can feed this straight into the renderer scope without coercion.
     */
    public Map<String, Object> defaultValues(EmailTemplateKey key) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (EmailTemplateField f : schemaFor(key)) {
            if (f.kind() == EmailTemplateField.Kind.LIST) {
                out.put(f.id(), f.defaultItems() == null ? List.of() : List.copyOf(f.defaultItems()));
            } else {
                out.put(f.id(), f.defaultValue());
            }
        }
        return out;
    }

    private List<EmailTemplateField> assessmentAssigned() {
        List<String> memberVars   = List.of("memberName");
        List<String> pipelineVars = List.of("pipelineName");
        return List.of(
                new EmailTemplateField(
                        "subject", "Subject line",
                        "The line recipients see in their inbox before opening the email.",
                        EmailTemplateField.Kind.RICH_TEXT, 160,
                        List.of("memberName", "pipelineName"),
                        "New Assessment Assigned: {{pipelineName}}",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "heading", "Heading",
                        "The large title shown at the top of the email body.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 120,
                        NO_VARS,
                        "New Assessment Assigned",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "greeting", "Greeting",
                        "The first line addressing the member. The name becomes bold automatically.",
                        EmailTemplateField.Kind.RICH_TEXT, 160,
                        memberVars,
                        "Hello {{memberName}},",
                        2, "Main Content", false, null),
                new EmailTemplateField(
                        "mainMessage", "Main message",
                        "The paragraph that explains why this email was sent.",
                        EmailTemplateField.Kind.RICH_TEXT, 600,
                        NO_VARS,
                        "You have been assigned a new assessment. Think of this as your \"before camera shot\" — a clear picture of where you stand today.",
                        2, "Main Content", false, null),
                new EmailTemplateField(
                        "cardHeading", "Assessment name shown in card",
                        "The pipeline title shown inside the highlighted card. Usually just the pipeline variable.",
                        EmailTemplateField.Kind.RICH_TEXT, 120,
                        pipelineVars,
                        "{{pipelineName}}",
                        3, "Assessment Card", false, null),
                new EmailTemplateField(
                        "deadlineWithValue", "Deadline line (when deadline is set)",
                        "Shown inside the card when a deadline exists. Use {{deadline}} to insert the date.",
                        EmailTemplateField.Kind.RICH_TEXT, 160,
                        List.of("deadline"),
                        "Deadline: {{deadline}}",
                        3, "Assessment Card", false, null),
                new EmailTemplateField(
                        "deadlineWithoutValue", "Deadline line (when no deadline)",
                        "Shown inside the card when no deadline is configured.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 160,
                        NO_VARS,
                        "No deadline — complete at your own pace",
                        3, "Assessment Card", false, null),
                new EmailTemplateField(
                        "ctaLabel", "Button label",
                        "Text on the main action button.",
                        EmailTemplateField.Kind.CTA_LABEL, 40,
                        NO_VARS,
                        "Start Your Assessment",
                        4, "Call to Action", false, null),
                new EmailTemplateField(
                        "tipsHeading", "Tips heading",
                        "Title above the list of tips.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 80,
                        NO_VARS,
                        "Tips for your assessment:",
                        5, "Tips", false, null),
                EmailTemplateField.list(
                        "tips", "Tips",
                        "Bullet points shown under the tips heading. Add as many as you want; remove all to hide the tips box.",
                        200, 10,
                        NO_VARS,
                        List.of(
                                "Be brutally honest — you're only cheating yourself otherwise",
                                "First instinct wins — don't overthink it",
                                "Your answers stay private",
                                "Your progress is saved automatically"
                        ),
                        5, "Tips", true, "tips")
        );
    }

    private List<EmailTemplateField> assessmentReminder() {
        List<String> memberVars   = List.of("memberName");
        List<String> pipelineVars = List.of("pipelineName");
        return List.of(
                new EmailTemplateField(
                        "subject", "Subject line",
                        "The line recipients see in their inbox.",
                        EmailTemplateField.Kind.RICH_TEXT, 160,
                        List.of("memberName", "pipelineName"),
                        "Reminder: Complete Your Assessment - {{pipelineName}}",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "heading", "Heading",
                        "The large title at the top of the email body.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 120,
                        NO_VARS,
                        "Friendly Reminder 🔔",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "greeting", "Greeting",
                        "First line addressing the member.",
                        EmailTemplateField.Kind.RICH_TEXT, 160,
                        memberVars,
                        "Hello {{memberName}},",
                        2, "Main Content", false, null),
                new EmailTemplateField(
                        "mainMessage", "Main message",
                        "The paragraph that nudges the member to continue.",
                        EmailTemplateField.Kind.RICH_TEXT, 600,
                        NO_VARS,
                        "You have an incomplete assessment waiting for you. Your progress has been saved — pick up right where you left off.",
                        2, "Main Content", false, null),
                new EmailTemplateField(
                        "cardHeading", "Assessment name shown in card",
                        "The pipeline title shown inside the highlighted card.",
                        EmailTemplateField.Kind.RICH_TEXT, 120,
                        pipelineVars,
                        "{{pipelineName}}",
                        3, "Assessment Card", false, null),
                new EmailTemplateField(
                        "deadlineWithValue", "Deadline line (when deadline is set)",
                        "Shown when a deadline exists. Use {{deadline}} to insert the date.",
                        EmailTemplateField.Kind.RICH_TEXT, 160,
                        List.of("deadline"),
                        "Deadline: {{deadline}}",
                        3, "Assessment Card", false, null),
                new EmailTemplateField(
                        "deadlineWithoutValue", "Deadline line (when no deadline)",
                        "Shown when no deadline is configured.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 160,
                        NO_VARS,
                        "No deadline — but don't lose momentum!",
                        3, "Assessment Card", false, null),
                new EmailTemplateField(
                        "ctaLabel", "Button label",
                        "Text on the main action button.",
                        EmailTemplateField.Kind.CTA_LABEL, 40,
                        NO_VARS,
                        "Continue Your Assessment",
                        4, "Call to Action", false, null)
        );
    }

    private List<EmailTemplateField> resultsReady() {
        List<String> memberVars   = List.of("memberName");
        List<String> pipelineVars = List.of("pipelineName");
        return List.of(
                new EmailTemplateField(
                        "subject", "Subject line",
                        "The line recipients see in their inbox.",
                        EmailTemplateField.Kind.RICH_TEXT, 160,
                        List.of("memberName", "pipelineName"),
                        "Your Assessment Results Are Ready: {{pipelineName}}",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "heading", "Heading",
                        "The celebratory title at the top of the email body.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 120,
                        NO_VARS,
                        "Your Results Are Ready! 🎉",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "greeting", "Greeting",
                        "First line addressing the member.",
                        EmailTemplateField.Kind.RICH_TEXT, 160,
                        memberVars,
                        "Hello {{memberName}},",
                        2, "Main Content", false, null),
                new EmailTemplateField(
                        "mainMessage", "Main message",
                        "The paragraph announcing the results are available.",
                        EmailTemplateField.Kind.RICH_TEXT, 600,
                        NO_VARS,
                        "Great news — your assessment has been evaluated and your results are now available.",
                        2, "Main Content", false, null),
                new EmailTemplateField(
                        "cardHeading", "Pipeline name shown in card",
                        "Usually just the pipeline variable.",
                        EmailTemplateField.Kind.RICH_TEXT, 120,
                        pipelineVars,
                        "{{pipelineName}}",
                        3, "Results Card", false, null),
                new EmailTemplateField(
                        "cardStatusLine", "Status line under the pipeline name",
                        "Short confirmation that the evaluation finished.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 80,
                        NO_VARS,
                        "Evaluation Complete",
                        3, "Results Card", false, null),
                new EmailTemplateField(
                        "ctaLabel", "Button label",
                        "Text on the main action button.",
                        EmailTemplateField.Kind.CTA_LABEL, 40,
                        NO_VARS,
                        "View Your Results",
                        4, "Call to Action", false, null),
                new EmailTemplateField(
                        "postCompletionHeading", "Follow-up heading",
                        "Title of the follow-up section (e.g. survey prompt). Hidden when no follow-up is configured for the pipeline and all fields in this section are left empty.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 80,
                        NO_VARS,
                        "One last step",
                        5, "Follow-up (optional)", true, "postCompletion"),
                new EmailTemplateField(
                        "postCompletionBody", "Follow-up body",
                        "Short explanation of the follow-up action.",
                        EmailTemplateField.Kind.RICH_TEXT, 300,
                        NO_VARS,
                        "Help us improve by completing a short follow-up.",
                        5, "Follow-up (optional)", true, "postCompletion")
        );
    }

    private List<EmailTemplateField> postAssessmentSurveyInvite() {
        List<String> memberVars   = List.of("memberName");
        List<String> pipelineVars = List.of("pipelineName");
        List<String> surveyVars   = List.of("surveyName");
        return List.of(
                new EmailTemplateField(
                        "subject", "Subject line",
                        "The line recipients see in their inbox.",
                        EmailTemplateField.Kind.RICH_TEXT, 160,
                        List.of("memberName", "pipelineName", "surveyName"),
                        "One last step for {{pipelineName}}: a quick survey",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "heading", "Heading",
                        "The large title shown at the top of the email body.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 120,
                        NO_VARS,
                        "Help us learn more",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "greeting", "Greeting",
                        "First line addressing the member. The name becomes bold automatically.",
                        EmailTemplateField.Kind.RICH_TEXT, 160,
                        memberVars,
                        "Hello {{memberName}},",
                        2, "Main Content", false, null),
                new EmailTemplateField(
                        "mainMessage", "Main message",
                        "The paragraph explaining why we're asking the member to take the survey.",
                        EmailTemplateField.Kind.RICH_TEXT, 600,
                        pipelineVars,
                        "Now that you've finished {{pipelineName}}, we'd love your feedback. Your responses are visible only to your organization administrator and help us tailor future assessments.",
                        2, "Main Content", false, null),
                new EmailTemplateField(
                        "cardHeading", "Survey name shown in card",
                        "Usually just the survey variable.",
                        EmailTemplateField.Kind.RICH_TEXT, 120,
                        surveyVars,
                        "{{surveyName}}",
                        3, "Survey Card", false, null),
                new EmailTemplateField(
                        "cardSubline", "Helper line under the survey name",
                        "Short note about the survey, e.g. how long it takes.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 120,
                        NO_VARS,
                        "Takes about 2 minutes",
                        3, "Survey Card", false, null),
                new EmailTemplateField(
                        "ctaLabel", "Button label",
                        "Text on the main action button.",
                        EmailTemplateField.Kind.CTA_LABEL, 40,
                        NO_VARS,
                        "Take the survey",
                        4, "Call to Action", false, null),
                new EmailTemplateField(
                        "secondaryLine", "Secondary line",
                        "Optional line below the button that links back to the assessment results.",
                        EmailTemplateField.Kind.RICH_TEXT, 240,
                        NO_VARS,
                        "You can also revisit your assessment results any time.",
                        4, "Call to Action", false, null)
        );
    }

    private List<EmailTemplateField> surveyGiftAssessment() {
        List<String> nameVars       = List.of("respondentName");
        List<String> surveyVars     = List.of("surveyName");
        List<String> assessmentVars = List.of("assessmentTitle");
        return List.of(
                new EmailTemplateField(
                        "subject", "Subject line",
                        "The line recipients see in their inbox.",
                        EmailTemplateField.Kind.RICH_TEXT, 160,
                        List.of("respondentName", "surveyName", "assessmentTitle"),
                        "A gift for you: {{assessmentTitle}}",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "heading", "Heading",
                        "The large title shown at the top of the email body.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 120,
                        NO_VARS,
                        "A gift to say thank you",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "greeting", "Greeting",
                        "First line addressing the respondent. The survey can be answered without a name, so keep this readable when {{respondentName}} is empty.",
                        EmailTemplateField.Kind.RICH_TEXT, 160,
                        nameVars,
                        "Hi there,",
                        2, "Main Content", false, null),
                new EmailTemplateField(
                        "mainMessage", "Main message",
                        "The paragraph explaining the gift.",
                        EmailTemplateField.Kind.RICH_TEXT, 600,
                        surveyVars,
                        "Thanks for completing {{surveyName}}. As a thank-you, we'd like to gift you a complimentary assessment — our way of helping you go further.",
                        2, "Main Content", false, null),
                new EmailTemplateField(
                        "cardHeading", "Assessment name shown in card",
                        "Usually just the assessment variable.",
                        EmailTemplateField.Kind.RICH_TEXT, 120,
                        assessmentVars,
                        "{{assessmentTitle}}",
                        3, "Assessment Card", false, null),
                new EmailTemplateField(
                        "cardSubline", "Helper line under the assessment name",
                        "Short note about the assessment, e.g. how long it takes.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 120,
                        NO_VARS,
                        "Free for you — takes about 10 minutes",
                        3, "Assessment Card", false, null),
                new EmailTemplateField(
                        "ctaLabel", "Button label",
                        "Text on the main action button.",
                        EmailTemplateField.Kind.CTA_LABEL, 40,
                        NO_VARS,
                        "Take your assessment",
                        4, "Call to Action", false, null)
        );
    }

    private List<EmailTemplateField> invitation() {
        List<String> inviterVars = List.of("inviterName");
        List<String> orgVars     = List.of("organizationName");
        List<String> expiryVars  = List.of("expiresAt");
        return List.of(
                new EmailTemplateField(
                        "subject", "Subject line",
                        "The line recipients see in their inbox.",
                        EmailTemplateField.Kind.RICH_TEXT, 160,
                        List.of("organizationName", "inviterName"),
                        "You've been invited to join {{organizationName}}",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "heading", "Heading",
                        "The large title at the top of the email body.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 120,
                        NO_VARS,
                        "You're Invited!",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "introLine", "Intro line",
                        "Explains who invited the recipient. The inviter name becomes bold automatically.",
                        EmailTemplateField.Kind.RICH_TEXT, 240,
                        inviterVars,
                        "{{inviterName}} has invited you to join their organization on BVisionRY:",
                        2, "Main Content", false, null),
                new EmailTemplateField(
                        "cardHeading", "Organization name shown in card",
                        "Usually just the organization variable.",
                        EmailTemplateField.Kind.RICH_TEXT, 120,
                        orgVars,
                        "{{organizationName}}",
                        3, "Organization Card", false, null),
                new EmailTemplateField(
                        "expiryLine", "Expiration line",
                        "Tells the recipient when the invite expires. Use {{expiresAt}} to insert the date.",
                        EmailTemplateField.Kind.RICH_TEXT, 160,
                        expiryVars,
                        "This invitation expires on {{expiresAt}}",
                        3, "Organization Card", false, null),
                new EmailTemplateField(
                        "ctaIntro", "Line before the button",
                        "Invites the recipient to click the button.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 240,
                        NO_VARS,
                        "Click the button below to accept your invitation and create your account:",
                        4, "Call to Action", false, null),
                new EmailTemplateField(
                        "ctaLabel", "Button label",
                        "Text on the main action button.",
                        EmailTemplateField.Kind.CTA_LABEL, 40,
                        NO_VARS,
                        "Accept Invitation",
                        4, "Call to Action", false, null),
                new EmailTemplateField(
                        "footerNote", "Safety note at the bottom",
                        "Reassures users who didn't expect an invite that they can ignore it.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 240,
                        NO_VARS,
                        "If you did not expect this invitation, you can safely ignore this email.",
                        5, "Closing", false, null)
        );
    }

    private List<EmailTemplateField> trialEndingSoon() {
        List<String> orgVars   = List.of("organizationName");
        List<String> daysVars  = List.of("daysLeft");
        List<String> endsVars  = List.of("trialEndsAt");
        return List.of(
                new EmailTemplateField(
                        "subject", "Subject line",
                        "The line recipients see in their inbox.",
                        EmailTemplateField.Kind.RICH_TEXT, 160,
                        List.of("organizationName", "daysLeft"),
                        "Your {{organizationName}} trial ends in {{daysLeft}} days",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "heading", "Heading",
                        "The large title at the top of the email body.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 120,
                        NO_VARS,
                        "Your trial is ending soon",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "introLine", "Intro line",
                        "Short line that introduces the situation. Use {{organizationName}} to insert the org name.",
                        EmailTemplateField.Kind.RICH_TEXT, 240,
                        orgVars,
                        "Heads up — the Premium trial for <strong>{{organizationName}}</strong> is wrapping up.",
                        2, "Main Content", false, null),
                new EmailTemplateField(
                        "cardHeading", "Days remaining headline",
                        "Big headline shown inside the highlighted card. Use {{daysLeft}} to insert the count.",
                        EmailTemplateField.Kind.RICH_TEXT, 120,
                        daysVars,
                        "{{daysLeft}} days left on your trial",
                        3, "Trial Card", false, null),
                new EmailTemplateField(
                        "cardSubLine", "End-date line",
                        "Sub-line in the card that shows the end date. Use {{trialEndsAt}} to insert it.",
                        EmailTemplateField.Kind.RICH_TEXT, 200,
                        endsVars,
                        "Your trial ends on {{trialEndsAt}}.",
                        3, "Trial Card", false, null),
                new EmailTemplateField(
                        "ctaIntro", "Line before the button",
                        "Short paragraph nudging the recipient to upgrade or contact us.",
                        EmailTemplateField.Kind.RICH_TEXT, 400,
                        NO_VARS,
                        "Want to keep all of your Premium features? Review your plan from the dashboard before time runs out.",
                        4, "Call to Action", false, null),
                new EmailTemplateField(
                        "ctaLabel", "Button label",
                        "Text on the main action button.",
                        EmailTemplateField.Kind.CTA_LABEL, 40,
                        NO_VARS,
                        "Open Dashboard",
                        4, "Call to Action", false, null),
                new EmailTemplateField(
                        "footerNote", "Closing note",
                        "Reassuring note at the bottom of the email.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 240,
                        NO_VARS,
                        "If you have any questions, just reply to this email — we're happy to help.",
                        5, "Closing", false, null)
        );
    }

    private List<EmailTemplateField> trialExpired() {
        List<String> orgVars     = List.of("organizationName");
        List<String> expiredVars = List.of("expiredAt");
        return List.of(
                new EmailTemplateField(
                        "subject", "Subject line",
                        "The line recipients see in their inbox.",
                        EmailTemplateField.Kind.RICH_TEXT, 160,
                        List.of("organizationName"),
                        "Your {{organizationName}} trial has ended",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "heading", "Heading",
                        "The large title at the top of the email body.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 120,
                        NO_VARS,
                        "Your trial has ended",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "introLine", "Intro line",
                        "Short line confirming the trial ended. Use {{organizationName}} to insert the org name.",
                        EmailTemplateField.Kind.RICH_TEXT, 240,
                        orgVars,
                        "The Premium trial for <strong>{{organizationName}}</strong> has ended.",
                        2, "Main Content", false, null),
                new EmailTemplateField(
                        "cardHeading", "Card heading",
                        "Headline shown inside the highlighted card.",
                        EmailTemplateField.Kind.RICH_TEXT, 120,
                        NO_VARS,
                        "Your account is now on the Free plan",
                        3, "Trial Card", false, null),
                new EmailTemplateField(
                        "cardSubLine", "End-date line",
                        "Sub-line in the card that shows when the trial ended. Use {{expiredAt}} to insert the date.",
                        EmailTemplateField.Kind.RICH_TEXT, 200,
                        expiredVars,
                        "Trial ended on {{expiredAt}}.",
                        3, "Trial Card", false, null),
                new EmailTemplateField(
                        "mainMessage", "Body paragraph",
                        "Explains what changed and what the recipient can do next.",
                        EmailTemplateField.Kind.RICH_TEXT, 600,
                        NO_VARS,
                        "Premium features are paused for your organization. Your data is safe and you can upgrade at any time to restore full access.",
                        2, "Main Content", false, null),
                new EmailTemplateField(
                        "ctaLabel", "Button label",
                        "Text on the main action button.",
                        EmailTemplateField.Kind.CTA_LABEL, 40,
                        NO_VARS,
                        "Upgrade to Premium",
                        4, "Call to Action", false, null),
                new EmailTemplateField(
                        "footerNote", "Closing note",
                        "Closing note at the bottom of the email.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 240,
                        NO_VARS,
                        "Thanks for trying BVisionRY — reply to this email if you'd like a hand picking the right plan.",
                        5, "Closing", false, null)
        );
    }

    private List<EmailTemplateField> upgradeRequested() {
        List<String> orgVars     = List.of("organizationName");
        return List.of(
                new EmailTemplateField(
                        "subject", "Subject line",
                        "The line recipients see in their inbox.",
                        EmailTemplateField.Kind.RICH_TEXT, 160,
                        List.of("organizationName", "memberName"),
                        "{{organizationName}} requested a Premium upgrade",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "heading", "Heading",
                        "The large title at the top of the email body.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 120,
                        NO_VARS,
                        "Premium upgrade requested",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "introLine", "Intro line",
                        "Short line introducing what happened. Use {{organizationName}} to insert the org name.",
                        EmailTemplateField.Kind.RICH_TEXT, 240,
                        orgVars,
                        "A member of <strong>{{organizationName}}</strong> just asked to upgrade to Premium.",
                        2, "Main Content", false, null),
                new EmailTemplateField(
                        "cardHeading", "Card heading",
                        "Headline shown at the top of the request details card.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 120,
                        NO_VARS,
                        "Request details",
                        3, "Request Card", false, null),
                new EmailTemplateField(
                        "mainMessage", "Body paragraph",
                        "Paragraph below the card encouraging the recipient to follow up.",
                        EmailTemplateField.Kind.RICH_TEXT, 600,
                        NO_VARS,
                        "Reach out directly to discuss the upgrade, or open the org dashboard to flip the tier.",
                        2, "Main Content", false, null),
                new EmailTemplateField(
                        "ctaLabel", "Button label",
                        "Text on the main action button.",
                        EmailTemplateField.Kind.CTA_LABEL, 40,
                        NO_VARS,
                        "Open org dashboard",
                        4, "Call to Action", false, null),
                new EmailTemplateField(
                        "footerNote", "Closing note",
                        "Closing note at the bottom of the email.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 240,
                        NO_VARS,
                        "Recipients of this notification can be customized in the platform admin settings.",
                        5, "Closing", false, null)
        );
    }

    private List<EmailTemplateField> leadMagnet() {
        return List.of(
                new EmailTemplateField(
                        "subject", "Subject line",
                        "The line recipients see in their inbox.",
                        EmailTemplateField.Kind.RICH_TEXT, 160,
                        NO_VARS,
                        "The science behind the 11 pillars of founder readiness",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "heading", "Heading",
                        "The large title shown at the top of the email body.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 120,
                        NO_VARS,
                        "Here's the science behind founder readiness",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "greeting", "Greeting",
                        "The first line that welcomes the reader.",
                        EmailTemplateField.Kind.RICH_TEXT, 160,
                        NO_VARS,
                        "Hi there,",
                        2, "Main Content", false, null),
                new EmailTemplateField(
                        "mainMessage", "Main message",
                        "The welcoming paragraph that introduces the attached research.",
                        EmailTemplateField.Kind.RICH_TEXT, 600,
                        NO_VARS,
                        "Thanks for your interest in the Founder Readiness Index. We've attached the research paper behind the 11 pillars — how each one is measured, tracked, and developed with real founders worldwide. Enjoy the read.",
                        2, "Main Content", false, null),
                new EmailTemplateField(
                        "cardHeading", "Attachment card heading",
                        "Headline of the highlighted card that points to the attachment.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 120,
                        NO_VARS,
                        "Your PDF is attached",
                        3, "Attachment Card", false, null),
                new EmailTemplateField(
                        "cardSubline", "Attachment card helper line",
                        "Short note under the card heading.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 160,
                        NO_VARS,
                        "Look for the PDF attached to this email — open it any time.",
                        3, "Attachment Card", false, null),
                new EmailTemplateField(
                        "footerNote", "Closing note",
                        "Closing line at the bottom of the email.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 240,
                        NO_VARS,
                        "If you have any questions, just reply to this email — we're happy to help.",
                        4, "Closing", false, null)
        );
    }

    private List<EmailTemplateField> contactMessage() {
        List<String> inquiryVars = List.of("inquiry");
        return List.of(
                new EmailTemplateField(
                        "subject", "Subject line",
                        "The line recipients see in their inbox.",
                        EmailTemplateField.Kind.RICH_TEXT, 160,
                        List.of("senderName", "inquiry"),
                        "New contact message from {{senderName}}",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "heading", "Heading",
                        "The large title at the top of the email body.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 120,
                        NO_VARS,
                        "New contact message",
                        1, "Basics", false, null),
                new EmailTemplateField(
                        "introLine", "Intro line",
                        "Short line introducing what happened. Use {{inquiry}} to insert the topic.",
                        EmailTemplateField.Kind.RICH_TEXT, 240,
                        inquiryVars,
                        "Someone reached out through the website contact form about <strong>{{inquiry}}</strong>.",
                        2, "Main Content", false, null),
                new EmailTemplateField(
                        "mainMessage", "Body paragraph",
                        "Paragraph below the card encouraging the recipient to follow up.",
                        EmailTemplateField.Kind.RICH_TEXT, 600,
                        NO_VARS,
                        "Reply directly to this person to continue the conversation.",
                        2, "Main Content", false, null),
                new EmailTemplateField(
                        "cardHeading", "Card heading",
                        "Headline shown at the top of the message details card.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 120,
                        NO_VARS,
                        "Message details",
                        3, "Message Card", false, null),
                new EmailTemplateField(
                        "ctaLabel", "Button label",
                        "Text on the main action button.",
                        EmailTemplateField.Kind.CTA_LABEL, 40,
                        NO_VARS,
                        "Reply by email",
                        4, "Call to Action", false, null),
                new EmailTemplateField(
                        "footerNote", "Closing note",
                        "Closing note at the bottom of the email.",
                        EmailTemplateField.Kind.PLAIN_TEXT, 240,
                        NO_VARS,
                        "Recipients of this notification can be customized in the platform admin settings.",
                        5, "Closing", false, null)
        );
    }
}
