package com.bvisionry.contact;

import com.bvisionry.notification.EmailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContactServiceTest {

    @Mock
    private ContactRecipientService contactRecipientService;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private ContactService contactService;

    private static ContactRequest sampleRequest() {
        return new ContactRequest(
                "Ada Lovelace",
                "ada@example.com",
                "Analytical Engines Ltd",
                "Partnership",
                "I'd like to discuss a partnership.");
    }

    @Test
    void submit_fansOutOneEmailPerRecipient_withMappedFields() {
        when(contactRecipientService.resolveRecipients())
                .thenReturn(List.of("admin1@bvisionry.com", "admin2@bvisionry.com"));

        contactService.submit(sampleRequest());

        verify(emailService).sendContactMessageAsync(
                "admin1@bvisionry.com", "Ada Lovelace", "ada@example.com",
                "Analytical Engines Ltd", "Partnership", "I'd like to discuss a partnership.");
        verify(emailService).sendContactMessageAsync(
                "admin2@bvisionry.com", "Ada Lovelace", "ada@example.com",
                "Analytical Engines Ltd", "Partnership", "I'd like to discuss a partnership.");
    }

    @Test
    void submit_noRecipients_dropsMessageWithoutSendingOrThrowing() {
        when(contactRecipientService.resolveRecipients()).thenReturn(List.of());

        // Fail-safe: no recipients and no super-admins => drop silently, never send.
        contactService.submit(sampleRequest());

        verify(emailService, never()).sendContactMessageAsync(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void submit_blankCompany_stillSends() {
        when(contactRecipientService.resolveRecipients())
                .thenReturn(List.of("admin@bvisionry.com"));

        ContactRequest noCompany = new ContactRequest(
                "Grace Hopper", "grace@example.com", null, "Support", "Help please.");
        contactService.submit(noCompany);

        verify(emailService).sendContactMessageAsync(
                "admin@bvisionry.com", "Grace Hopper", "grace@example.com",
                null, "Support", "Help please.");
    }
}
