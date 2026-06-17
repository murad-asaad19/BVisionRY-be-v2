package com.bvisionry.platform;

import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.organization.OrgAuditActions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared read/normalize/persist/audit logic for comma-delimited recipient lists
 * stored as a {@link PlatformSetting} {@code valueText}, keyed by a setting key.
 * When no explicit list is configured, callers can fall back to every
 * SUPER_ADMIN so the feature works out-of-the-box.
 */
@Component
@RequiredArgsConstructor
public class RecipientListSettings {

    private final PlatformSettingRepository settingRepo;
    private final UserRepository userRepo;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<String> readConfigured(String settingKey) {
        return settingRepo.findById(settingKey)
                .map(PlatformSetting::getValueText)
                .filter(s -> s != null && !s.isBlank())
                .map(s -> Arrays.stream(s.split(","))
                        .map(String::trim)
                        .filter(e -> !e.isEmpty())
                        .toList())
                .orElse(List.of());
    }

    @Transactional(readOnly = true)
    public List<String> resolveWithSuperAdminFallback(String settingKey) {
        List<String> configured = readConfigured(settingKey);
        if (!configured.isEmpty()) {
            return configured;
        }
        return userRepo.findByRole(UserRole.SUPER_ADMIN).stream()
                .map(User::getEmail)
                .toList();
    }

    @Transactional
    public List<String> save(String settingKey, List<String> recipients, UUID actorId, String auditAction) {
        List<String> normalized = normalize(recipients);
        String newValue = normalized.isEmpty() ? null : String.join(",", normalized);

        PlatformSetting setting = settingRepo.findById(settingKey).orElseGet(() -> {
            PlatformSetting fresh = new PlatformSetting();
            fresh.setKey(settingKey);
            return fresh;
        });
        if (Objects.equals(setting.getValueText(), newValue)) {
            return normalized;
        }
        setting.setValueText(newValue);
        setting.setValueInt(null);
        setting.setUpdatedAt(Instant.now());
        setting.setUpdatedBy(actorId);
        settingRepo.save(setting);

        auditService.log(actorId, null, auditAction,
                OrgAuditActions.ENTITY_PLATFORM, null,
                Map.of("recipients", normalized, "fallbackToSuperAdmins", normalized.isEmpty()));

        return normalized;
    }

    private List<String> normalize(List<String> raw) {
        if (raw == null) return List.of();
        return raw.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase())
                .distinct()
                .toList();
    }
}
