package com.bvisionry.organization;

import com.bvisionry.audit.AuditService;
import com.bvisionry.config.CacheConfig;
import com.bvisionry.platform.PlatformSetting;
import com.bvisionry.platform.PlatformSettingRepository;
import com.bvisionry.platform.dto.AttentionThresholdsRequest;
import com.bvisionry.platform.dto.AttentionThresholdsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reads & writes the platform-wide thresholds that drive the {@link AttentionRuleService}
 * "Needs your attention" rules. Defaults match the constants the rules historically
 * used so the system behaves identically on a fresh install.
 *
 * Reads are cached on the {@code platformSettings} region; writes evict the entire
 * region so subsequent reads pick up the new values immediately.
 */
@Service
@RequiredArgsConstructor
public class AttentionThresholdsService {

    static final String KEY_SUSPENDED_DAYS                  = "attention.suspended_days";
    static final String KEY_TRIAL_EXPIRY_WINDOW_DAYS        = "attention.trial_expiry_window_days";
    static final String KEY_TRIAL_JUST_EXPIRED_WINDOW_DAYS  = "attention.trial_just_expired_window_days";
    static final String KEY_IDLE_DAYS                       = "attention.idle_days";
    static final String KEY_ONBOARDING_STALLED_HOURS        = "attention.onboarding_stalled_hours";

    static final int DEFAULT_SUSPENDED_DAYS                  = 7;
    static final int DEFAULT_TRIAL_EXPIRY_WINDOW_DAYS        = 7;
    static final int DEFAULT_TRIAL_JUST_EXPIRED_WINDOW_DAYS  = 30;
    static final int DEFAULT_IDLE_DAYS                       = 14;
    static final int DEFAULT_ONBOARDING_STALLED_HOURS        = 24;

    private final PlatformSettingRepository repo;
    private final AuditService auditService;

    @Cacheable(value = CacheConfig.PLATFORM_SETTINGS, key = "'attentionThresholds'")
    @Transactional(readOnly = true)
    public AttentionThresholdsResponse get() {
        return new AttentionThresholdsResponse(
                read(KEY_SUSPENDED_DAYS,                 DEFAULT_SUSPENDED_DAYS),
                read(KEY_TRIAL_EXPIRY_WINDOW_DAYS,       DEFAULT_TRIAL_EXPIRY_WINDOW_DAYS),
                read(KEY_TRIAL_JUST_EXPIRED_WINDOW_DAYS, DEFAULT_TRIAL_JUST_EXPIRED_WINDOW_DAYS),
                read(KEY_IDLE_DAYS,                      DEFAULT_IDLE_DAYS),
                read(KEY_ONBOARDING_STALLED_HOURS,       DEFAULT_ONBOARDING_STALLED_HOURS)
        );
    }

    public int suspendedDays()              { return get().suspendedDays(); }
    public int trialExpiryWindowDays()      { return get().trialExpiryWindowDays(); }
    public int trialJustExpiredWindowDays() { return get().trialJustExpiredWindowDays(); }
    public int idleDays()                   { return get().idleDays(); }
    public int onboardingStalledHours()     { return get().onboardingStalledHours(); }

    @Caching(evict = {
            // The thresholds themselves changed — drop the cached read.
            @CacheEvict(value = CacheConfig.PLATFORM_SETTINGS, allEntries = true),
            // Dashboard payload bakes the threshold values into headlines like
            // "Idle for over 14 days", so it must refresh too.
            @CacheEvict(value = CacheConfig.DASHBOARD, allEntries = true)
    })
    @Transactional
    public AttentionThresholdsResponse setAll(AttentionThresholdsRequest req, UUID actorId) {
        Instant now = Instant.now();
        write(KEY_SUSPENDED_DAYS,                 req.suspendedDays(),                 actorId, now);
        write(KEY_TRIAL_EXPIRY_WINDOW_DAYS,       req.trialExpiryWindowDays(),         actorId, now);
        write(KEY_TRIAL_JUST_EXPIRED_WINDOW_DAYS, req.trialJustExpiredWindowDays(),    actorId, now);
        write(KEY_IDLE_DAYS,                      req.idleDays(),                      actorId, now);
        write(KEY_ONBOARDING_STALLED_HOURS,       req.onboardingStalledHours(),        actorId, now);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("suspendedDays",                 req.suspendedDays());
        details.put("trialExpiryWindowDays",         req.trialExpiryWindowDays());
        details.put("trialJustExpiredWindowDays",    req.trialJustExpiredWindowDays());
        details.put("idleDays",                      req.idleDays());
        details.put("onboardingStalledHours",        req.onboardingStalledHours());
        auditService.log(actorId, null, OrgAuditActions.ATTENTION_THRESHOLDS_UPDATED,
                OrgAuditActions.ENTITY_PLATFORM, null, details);

        return new AttentionThresholdsResponse(
                req.suspendedDays(),
                req.trialExpiryWindowDays(),
                req.trialJustExpiredWindowDays(),
                req.idleDays(),
                req.onboardingStalledHours()
        );
    }

    private int read(String key, int fallback) {
        return repo.findById(key).map(PlatformSetting::getValueInt).orElse(fallback);
    }

    private void write(String key, int value, UUID actorId, Instant now) {
        PlatformSetting setting = repo.findById(key).orElseGet(() -> {
            PlatformSetting fresh = new PlatformSetting();
            fresh.setKey(key);
            return fresh;
        });
        setting.setValueInt(value);
        setting.setUpdatedAt(now);
        setting.setUpdatedBy(actorId);
        repo.save(setting);
    }
}
