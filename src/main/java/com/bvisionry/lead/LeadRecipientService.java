package com.bvisionry.lead;

import com.bvisionry.organization.OrgAuditActions;
import com.bvisionry.platform.RecipientListSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Resolves and persists the recipient list for demo-request (Book-a-Demo)
 * lead notifications. When no explicit list is configured, every SUPER_ADMIN
 * is used so the feature works out-of-the-box.
 */
@Service
@RequiredArgsConstructor
public class LeadRecipientService {

    static final String SETTING_KEY = "notifications.lead_recipients";

    private final RecipientListSettings settings;

    @Transactional(readOnly = true)
    public List<String> resolveRecipients() {
        return settings.resolveWithSuperAdminFallback(SETTING_KEY);
    }

    @Transactional(readOnly = true)
    public RecipientsView get() {
        List<String> configured = settings.readConfigured(SETTING_KEY);
        return new RecipientsView(configured, configured.isEmpty());
    }

    @Transactional
    public RecipientsView setRecipients(List<String> recipients, UUID actorId) {
        List<String> saved = settings.save(SETTING_KEY, recipients, actorId,
                OrgAuditActions.LEAD_RECIPIENTS_UPDATED);
        return new RecipientsView(saved, saved.isEmpty());
    }

    public record RecipientsView(List<String> recipients, boolean fallbackToSuperAdmins) {}
}
