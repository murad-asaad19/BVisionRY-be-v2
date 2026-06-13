package com.bvisionry.upgrade;

import com.bvisionry.audit.AuditService;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.organization.OrgAuditActions;
import com.bvisionry.platform.PlatformSetting;
import com.bvisionry.platform.PlatformSettingRepository;
import com.bvisionry.upgrade.UpgradePromptLoader.UpgradePrompt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the in-app upgrade prompt copy with a SUPER_ADMIN-editable override
 * layered on top of the shipped {@link UpgradePromptLoader} defaults. The
 * override lives in a single {@code platform_settings} row keyed by
 * {@link #SETTING_KEY} and fully replaces the file defaults when present.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UpgradePromptService {

    static final String SETTING_KEY = "upgrade.prompt";

    private static final int COOLDOWN_MIN_HOURS = 1;
    private static final int COOLDOWN_MAX_HOURS = 720;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UpgradePromptLoader loader;
    private final PlatformSettingRepository settingRepo;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public UpgradePrompt get() {
        return readOverride().orElseGet(loader::get);
    }

    @Transactional(readOnly = true)
    public View view() {
        return readOverride()
                .map(p -> new View(p, true))
                .orElseGet(() -> new View(loader.get(), false));
    }

    @Transactional
    public UpgradePrompt set(UpgradePrompt prompt, UUID actorId) {
        validate(prompt);
        String json;
        try {
            json = MAPPER.writeValueAsString(prompt);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Failed to serialize upgrade prompt: " + e.getMessage());
        }

        PlatformSetting setting = settingRepo.findById(SETTING_KEY).orElseGet(() -> {
            PlatformSetting fresh = new PlatformSetting();
            fresh.setKey(SETTING_KEY);
            return fresh;
        });
        if (Objects.equals(setting.getValueText(), json)) {
            return prompt;
        }
        setting.setValueText(json);
        setting.setValueInt(null);
        setting.setUpdatedAt(Instant.now());
        setting.setUpdatedBy(actorId);
        settingRepo.save(setting);

        auditService.log(actorId, null, OrgAuditActions.UPGRADE_PROMPT_UPDATED,
                OrgAuditActions.ENTITY_PLATFORM, null,
                Map.of("cooldownHours", prompt.cooldownHours(),
                        "bulletCount", prompt.bullets().size()));

        return prompt;
    }

    @Transactional
    public UpgradePrompt reset(UUID actorId) {
        if (settingRepo.existsById(SETTING_KEY)) {
            settingRepo.deleteById(SETTING_KEY);
            auditService.log(actorId, null, OrgAuditActions.UPGRADE_PROMPT_RESET,
                    OrgAuditActions.ENTITY_PLATFORM, null, Map.of());
        }
        return loader.get();
    }

    private Optional<UpgradePrompt> readOverride() {
        return settingRepo.findById(SETTING_KEY)
                .map(PlatformSetting::getValueText)
                .filter(s -> s != null && !s.isBlank())
                .flatMap(this::tryParse);
    }

    private Optional<UpgradePrompt> tryParse(String json) {
        try {
            return Optional.of(MAPPER.readValue(json, UpgradePrompt.class));
        } catch (JsonProcessingException e) {
            log.warn("Stored upgrade prompt override under key '{}' is unparseable; "
                    + "falling back to file defaults: {}", SETTING_KEY, e.getOriginalMessage());
            return Optional.empty();
        }
    }

    private void validate(UpgradePrompt prompt) {
        if (prompt == null) {
            throw new BadRequestException("Upgrade prompt body is required.");
        }
        requireText("headline", prompt.headline());
        requireText("noteLabel", prompt.noteLabel());
        requireText("notePlaceholder", prompt.notePlaceholder());
        requireText("buttonLabel", prompt.buttonLabel());
        requireText("helperText", prompt.helperText());
        requireText("cooldownHeadline", prompt.cooldownHeadline());
        requireText("cooldownBody", prompt.cooldownBody());

        List<String> bullets = prompt.bullets();
        if (bullets == null || bullets.isEmpty()) {
            throw new BadRequestException("At least one bullet is required.");
        }
        for (int i = 0; i < bullets.size(); i++) {
            String b = bullets.get(i);
            if (b == null || b.isBlank()) {
                throw new BadRequestException("Bullet " + (i + 1) + " must not be blank.");
            }
        }

        int hours = prompt.cooldownHours();
        if (hours < COOLDOWN_MIN_HOURS || hours > COOLDOWN_MAX_HOURS) {
            throw new BadRequestException(
                    "cooldownHours must be between " + COOLDOWN_MIN_HOURS
                            + " and " + COOLDOWN_MAX_HOURS + " (got " + hours + ").");
        }
    }

    private static void requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " must not be blank.");
        }
    }

    public record View(UpgradePrompt prompt, boolean overridden) {}
}
