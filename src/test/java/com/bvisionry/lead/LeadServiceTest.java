package com.bvisionry.lead;

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
class LeadServiceTest {

    @Mock
    private LeadRepository leadRepository;

    @Mock
    private LeadRecipientService leadRecipientService;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private LeadService leadService;

    private static CreateLeadRequest sampleRequest() {
        return new CreateLeadRequest(
                "Ada Lovelace",
                "ada@example.com",
                "Analytical Engines Ltd",
                "Program Director",
                "Accelerator",
                "1-50",
                "We'd like a demo for our next cohort.",
                "book-demo-modal");
    }

    private void stubSaveReturnsArgument() {
        when(leadRepository.save(any(Lead.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void create_savesLead_andFansOutOneEmailPerRecipient() {
        stubSaveReturnsArgument();
        when(leadRecipientService.resolveRecipients())
                .thenReturn(List.of("admin1@bvisionry.com", "admin2@bvisionry.com"));

        leadService.create(sampleRequest());

        verify(emailService).sendDemoRequestAsync(
                "admin1@bvisionry.com", "Ada Lovelace", "ada@example.com",
                "Analytical Engines Ltd", "Program Director", "Accelerator",
                "1-50", "book-demo-modal", "We'd like a demo for our next cohort.");
        verify(emailService).sendDemoRequestAsync(
                "admin2@bvisionry.com", "Ada Lovelace", "ada@example.com",
                "Analytical Engines Ltd", "Program Director", "Accelerator",
                "1-50", "book-demo-modal", "We'd like a demo for our next cohort.");
    }

    @Test
    void create_noRecipients_savesLeadWithoutSending() {
        stubSaveReturnsArgument();
        when(leadRecipientService.resolveRecipients()).thenReturn(List.of());

        // Fail-safe: no recipients and no super-admins => the lead is still
        // persisted, the notification is dropped silently.
        leadService.create(sampleRequest());

        verify(leadRepository).save(any(Lead.class));
        verify(emailService, never()).sendDemoRequestAsync(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void create_recipientResolutionFailure_stillSavesLead() {
        stubSaveReturnsArgument();
        when(leadRecipientService.resolveRecipients())
                .thenThrow(new RuntimeException("settings unavailable"));

        // Notification failure must never roll back or fail the lead save.
        leadService.create(sampleRequest());

        verify(leadRepository).save(any(Lead.class));
    }
}
