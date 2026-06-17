package com.bvisionry.contact;

import com.bvisionry.notification.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContactService {

    private final ContactRecipientService contactRecipientService;
    private final EmailService emailService;

    /**
     * Fires a notification email to each configured contact recipient (with
     * super-admin fallback). Nothing is persisted — the contact form is a
     * fire-and-forget notification, not a tracked entity.
     *
     * <p>If no recipients are configured and no super-admins exist, the message
     * is dropped with a warning rather than failing the request.
     */
    public void submit(ContactRequest request) {
        List<String> recipients = contactRecipientService.resolveRecipients();
        if (recipients.isEmpty()) {
            log.warn("Contact message from {} dropped: no recipients configured and no super-admins exist",
                    request.email());
            return;
        }

        for (String to : recipients) {
            emailService.sendContactMessageAsync(
                    to,
                    request.fullName(),
                    request.email(),
                    request.company(),
                    request.inquiry(),
                    request.message());
        }
    }
}
