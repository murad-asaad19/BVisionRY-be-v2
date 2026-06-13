package com.bvisionry.upgrade;

import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.organization.OrgAuditActions;
import com.bvisionry.platform.PlatformSetting;
import com.bvisionry.platform.PlatformSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Resolves and persists the recipient list for {@code UPGRADE_REQUESTED}
 * notifications. When no explicit list is configured, every SUPER_ADMIN is
 * used so the feature works out-of-the-box.
 */
@Service
@RequiredArgsConstructor
public class UpgradeRequestRecipientService {

    static final String SETTING_KEY = "notifications.upgrade_request_recipients";

    private final PlatformSettingRepository settingRepo;
    private final UserRepository userRepo;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<String> resolveRecipients() {
        List<String> configured = readConfigured();
        if (!configured.isEmpty()) {
            return configured;
        }
        return userRepo.findByRole(UserRole.SUPER_ADMIN).stream()
                .map(User::getEmail)
                .toList();
    }

    @Transactional(readOnly = true)
    public RecipientsView get() {
        List<String> configured = readConfigured();
        return new RecipientsView(configured, configured.isEmpty());
    }

    @Transactional
    public RecipientsView setRecipients(List<String> recipients, UUID actorId) {
        List<String> normalized = normalize(recipients);
        String newValue = normalized.isEmpty() ? null : String.join(",", normalized);

        PlatformSetting setting = settingRepo.findById(SETTING_KEY).orElseGet(() -> {
            PlatformSetting fresh = new PlatformSetting();
            fresh.setKey(SETTING_KEY);
            return fresh;
        });
        if (Objects.equals(setting.getValueText(), newValue)) {
            return new RecipientsView(normalized, normalized.isEmpty());
        }
        setting.setValueText(newValue);
        setting.setValueInt(null);
        setting.setUpdatedAt(Instant.now());
        setting.setUpdatedBy(actorId);
        settingRepo.save(setting);

        auditService.log(actorId, null, OrgAuditActions.UPGRADE_REQUEST_RECIPIENTS_UPDATED,
                OrgAuditActions.ENTITY_PLATFORM, null,
                Map.of("recipients", normalized, "fallbackToSuperAdmins", normalized.isEmpty()));

        return new RecipientsView(normalized, normalized.isEmpty());
    }

    private List<String> readConfigured() {
        return settingRepo.findById(SETTING_KEY)
                .map(PlatformSetting::getValueText)
                .filter(s -> s != null && !s.isBlank())
                .map(s -> Arrays.stream(s.split(","))
                        .map(String::trim)
                        .filter(e -> !e.isEmpty())
                        .toList())
                .orElse(List.of());
    }

    private List<String> normalize(List<String> raw) {
        if (raw == null) return List.of();
        return raw.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase())
                .distinct()
                .toList();
    }

    public record RecipientsView(List<String> recipients, boolean fallbackToSuperAdmins) {}
}
