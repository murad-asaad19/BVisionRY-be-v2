package com.bvisionry.organization;

import com.bvisionry.audit.AuditService;
import com.bvisionry.platform.PlatformSetting;
import com.bvisionry.platform.PlatformSettingRepository;
import com.bvisionry.platform.dto.AttentionThresholdsRequest;
import com.bvisionry.platform.dto.AttentionThresholdsResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttentionThresholdsServiceTest {

    @Mock PlatformSettingRepository repo;
    @Mock AuditService auditService;

    @InjectMocks AttentionThresholdsService service;

    @Test
    void get_returnsHardcodedDefaultsWhenNoRows() {
        when(repo.findById(any())).thenReturn(Optional.empty());

        AttentionThresholdsResponse resp = service.get();

        assertThat(resp.suspendedDays()).isEqualTo(AttentionThresholdsService.DEFAULT_SUSPENDED_DAYS);
        assertThat(resp.trialExpiryWindowDays()).isEqualTo(AttentionThresholdsService.DEFAULT_TRIAL_EXPIRY_WINDOW_DAYS);
        assertThat(resp.trialJustExpiredWindowDays()).isEqualTo(AttentionThresholdsService.DEFAULT_TRIAL_JUST_EXPIRED_WINDOW_DAYS);
        assertThat(resp.idleDays()).isEqualTo(AttentionThresholdsService.DEFAULT_IDLE_DAYS);
        assertThat(resp.onboardingStalledHours()).isEqualTo(AttentionThresholdsService.DEFAULT_ONBOARDING_STALLED_HOURS);
        assertThat(resp.pillarThreshold()).isEqualTo(AttentionThresholdsService.DEFAULT_PILLAR_THRESHOLD);
    }

    @Test
    void get_readsValuesFromRepositoryWhenPresent() {
        Map<String, Integer> stored = Map.of(
                AttentionThresholdsService.KEY_SUSPENDED_DAYS, 14,
                AttentionThresholdsService.KEY_TRIAL_EXPIRY_WINDOW_DAYS, 3,
                AttentionThresholdsService.KEY_TRIAL_JUST_EXPIRED_WINDOW_DAYS, 60,
                AttentionThresholdsService.KEY_IDLE_DAYS, 21,
                AttentionThresholdsService.KEY_ONBOARDING_STALLED_HOURS, 48
        );
        stored.forEach((key, val) -> when(repo.findById(key)).thenReturn(Optional.of(setting(key, val))));

        AttentionThresholdsResponse resp = service.get();

        assertThat(resp.suspendedDays()).isEqualTo(14);
        assertThat(resp.trialExpiryWindowDays()).isEqualTo(3);
        assertThat(resp.trialJustExpiredWindowDays()).isEqualTo(60);
        assertThat(resp.idleDays()).isEqualTo(21);
        assertThat(resp.onboardingStalledHours()).isEqualTo(48);
    }

    @Test
    void setAll_persistsEachValueAndAudits() {
        UUID actor = UUID.randomUUID();
        AttentionThresholdsRequest req = new AttentionThresholdsRequest(10, 5, 45, 21, 36, 55);

        // For new rows the service constructs a fresh entity.
        when(repo.findById(any())).thenReturn(Optional.empty());

        AttentionThresholdsResponse resp = service.setAll(req, actor);

        // One save per key (6 keys total).
        ArgumentCaptor<PlatformSetting> saved = ArgumentCaptor.forClass(PlatformSetting.class);
        verify(repo, org.mockito.Mockito.times(6)).save(saved.capture());

        Map<String, Integer> persisted = new HashMap<>();
        for (PlatformSetting s : saved.getAllValues()) {
            persisted.put(s.getKey(), s.getValueInt());
            assertThat(s.getUpdatedBy()).isEqualTo(actor);
            assertThat(s.getUpdatedAt()).isNotNull();
        }
        assertThat(persisted)
                .containsEntry(AttentionThresholdsService.KEY_SUSPENDED_DAYS, 10)
                .containsEntry(AttentionThresholdsService.KEY_TRIAL_EXPIRY_WINDOW_DAYS, 5)
                .containsEntry(AttentionThresholdsService.KEY_TRIAL_JUST_EXPIRED_WINDOW_DAYS, 45)
                .containsEntry(AttentionThresholdsService.KEY_IDLE_DAYS, 21)
                .containsEntry(AttentionThresholdsService.KEY_ONBOARDING_STALLED_HOURS, 36)
                .containsEntry(AttentionThresholdsService.KEY_PILLAR_THRESHOLD, 55);

        // One audit row with the action constant.
        verify(auditService).log(eq(actor), eq(null), eq(OrgAuditActions.ATTENTION_THRESHOLDS_UPDATED),
                eq(OrgAuditActions.ENTITY_PLATFORM), eq(null), any());

        // Response mirrors the request.
        assertThat(resp.suspendedDays()).isEqualTo(10);
        assertThat(resp.idleDays()).isEqualTo(21);
    }

    @Test
    void setAll_isAnnotatedWithCacheEvict() throws NoSuchMethodException {
        // Cache eviction itself is enforced by Spring's CacheAspectSupport, which
        // requires a Spring context to exercise. Verifying the annotation here is
        // a low-cost guarantee that the wiring stays in place.
        Method m = AttentionThresholdsService.class.getMethod(
                "setAll", AttentionThresholdsRequest.class, UUID.class);
        org.springframework.cache.annotation.Caching caching =
                m.getAnnotation(org.springframework.cache.annotation.Caching.class);
        assertThat(caching).as("setAll must declare cache evictions").isNotNull();

        java.util.List<String> evictedRegions = new java.util.ArrayList<>();
        for (org.springframework.cache.annotation.CacheEvict evict : caching.evict()) {
            assertThat(evict.allEntries()).isTrue();
            for (String region : evict.value()) {
                evictedRegions.add(region);
            }
        }
        // Both the platformSettings and dashboard caches must be evicted: dashboard
        // payload contains threshold-derived headlines like "Idle for over N days".
        assertThat(evictedRegions).contains("platformSettings", "dashboard");
    }

    @Test
    void get_isAnnotatedWithCacheable() throws NoSuchMethodException {
        Method m = AttentionThresholdsService.class.getMethod("get");
        org.springframework.cache.annotation.Cacheable cacheable =
                m.getAnnotation(org.springframework.cache.annotation.Cacheable.class);
        assertThat(cacheable).as("get must read from the platformSettings cache region").isNotNull();
        assertThat(cacheable.value()).contains("platformSettings");
    }

    @Test
    void anAbsentPillarThresholdIsAViolation_notASilentZero() {
        // pillarThreshold=0 is the OFF value for the needs-attention pillar rule,
        // so a primitive int binding an absent field to 0 would let an
        // incomplete request shut the rule down platform-wide.
        try (jakarta.validation.ValidatorFactory factory =
                     jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            jakarta.validation.Validator validator = factory.getValidator();

            var violations = validator.validate(
                    new AttentionThresholdsRequest(7, 7, 30, 14, 24, null));
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .isEqualTo("pillarThreshold");

            assertThat(validator.validate(
                    new AttentionThresholdsRequest(10, 5, 45, 21, 36, 55))).isEmpty();
        }
    }

    private PlatformSetting setting(String key, int value) {
        PlatformSetting s = new PlatformSetting();
        s.setKey(key);
        s.setValueInt(value);
        return s;
    }
}
