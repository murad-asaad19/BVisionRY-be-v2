package com.bvisionry.lead;

import com.bvisionry.notification.transport.MailTransport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LeadService {

    private static final String NOTIFY_TO = "hello@bvisionry.com";
    private static final String NOTIFY_FROM = "no-reply@bvisionry.local";

    private final LeadRepository leadRepository;
    private final MailTransport mailTransport;

    /**
     * Persists the lead and fires a notification email to the sales inbox.
     * Mail failure is caught and logged — it never rolls back the lead save.
     *
     * @return the generated lead id
     */
    public UUID create(CreateLeadRequest request) {
        Lead lead = new Lead();
        lead.setName(request.name());
        lead.setEmail(request.email());
        lead.setOrganization(request.organization());
        lead.setRole(request.role());
        lead.setProgramType(request.programType());
        lead.setCohortSize(request.cohortSize());
        lead.setMessage(request.message());
        lead.setSource(request.source());

        Lead saved = leadRepository.save(lead);
        log.info("Lead saved: id={} org={}", saved.getId(), saved.getOrganization());

        try {
            sendNotification(saved);
        } catch (Exception e) {
            log.warn("Demo-request notification email failed (lead {} still saved): {}",
                    saved.getId(), e.getMessage());
        }

        return saved.getId();
    }

    private void sendNotification(Lead lead) {
        String subject = "New demo request: " + lead.getOrganization();
        String body = buildEmailBody(lead);
        mailTransport.send(NOTIFY_TO, subject, body);
    }

    private String buildEmailBody(Lead lead) {
        return """
                <html><body style="font-family:sans-serif;color:#1a1a1a;line-height:1.6">
                <h2 style="color:#060021">New Book-a-Demo Request</h2>
                <table cellpadding="6" cellspacing="0">
                  <tr><th align="left">Name</th><td>%s</td></tr>
                  <tr><th align="left">Email</th><td><a href="mailto:%s">%s</a></td></tr>
                  <tr><th align="left">Organization</th><td>%s</td></tr>
                  <tr><th align="left">Role</th><td>%s</td></tr>
                  <tr><th align="left">Program Type</th><td>%s</td></tr>
                  <tr><th align="left">Cohort Size</th><td>%s</td></tr>
                  <tr><th align="left">Source</th><td>%s</td></tr>
                </table>
                <h3 style="margin-top:16px">Message</h3>
                <p style="white-space:pre-wrap">%s</p>
                </body></html>
                """.formatted(
                escape(lead.getName()),
                escape(lead.getEmail()), escape(lead.getEmail()),
                escape(lead.getOrganization()),
                escape(lead.getRole()),
                escape(lead.getProgramType()),
                lead.getCohortSize() != null ? escape(lead.getCohortSize()) : "—",
                lead.getSource() != null ? escape(lead.getSource()) : "—",
                escape(lead.getMessage())
        );
    }

    /** Minimal HTML escaping — prevents XSS in internal notification emails. */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
