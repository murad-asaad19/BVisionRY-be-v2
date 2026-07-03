package com.bvisionry.lead;

import com.bvisionry.notification.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LeadService {

    private final LeadRepository leadRepository;
    private final LeadRecipientService leadRecipientService;
    private final EmailService emailService;

    /**
     * Persists the lead and fires a notification email to each configured
     * demo-request recipient (with super-admin fallback). Mail failure is
     * caught and logged — it never rolls back the lead save.
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
            notifyRecipients(saved);
        } catch (Exception e) {
            log.warn("Demo-request notification email failed (lead {} still saved): {}",
                    saved.getId(), e.getMessage());
        }

        return saved.getId();
    }

    /**
     * If no recipients are configured and no super-admins exist, the
     * notification is dropped with a warning — the lead itself is already
     * persisted, so nothing is lost.
     */
    private void notifyRecipients(Lead lead) {
        List<String> recipients = leadRecipientService.resolveRecipients();
        if (recipients.isEmpty()) {
            log.warn("Demo-request notification for lead {} dropped: no recipients configured and no super-admins exist",
                    lead.getId());
            return;
        }

        for (String to : recipients) {
            emailService.sendDemoRequestAsync(
                    to,
                    lead.getName(),
                    lead.getEmail(),
                    lead.getOrganization(),
                    lead.getRole(),
                    lead.getProgramType(),
                    lead.getCohortSize(),
                    lead.getSource(),
                    lead.getMessage());
        }
    }
}
