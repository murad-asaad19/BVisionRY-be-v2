package com.bvisionry.organization;

import com.bvisionry.audit.AuditRepository;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.config.CacheConfig;
import com.bvisionry.organization.dto.AttentionItem;
import com.bvisionry.organization.dto.DashboardResponse;
import com.bvisionry.organization.dto.KpiBlock;
import com.bvisionry.organization.dto.TierMix;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final OrganizationRepository orgRepo;
    private final UserRepository userRepo;
    private final AuditRepository auditRepo;
    private final AttentionRuleService attentionService;

    @Cacheable(value = CacheConfig.DASHBOARD)
    public DashboardResponse getDashboard() {
        Instant now = Instant.now();
        Instant in7d = now.plus(7, ChronoUnit.DAYS);
        Instant minus30d = now.minus(30, ChronoUnit.DAYS);
        Instant minus7d = now.minus(7, ChronoUnit.DAYS);

        // ROOT orgs only: sub-orgs are internal subdivisions of a customer and
        // would double-count org totals, retention, and the tier mix (they're
        // always FREE on their own row — their tier is inherited). Trials can
        // only exist on root orgs, so those counts need no root filter.
        long totalOrgs = orgRepo.countByParentOrganizationIsNull();
        long activeCount = orgRepo.countByIsActiveTrueAndParentOrganizationIsNull();
        long suspendedCount = totalOrgs - activeCount;
        long onTrial = orgRepo.countOnActiveTrial(now);
        long trialsExpiring = orgRepo.countTrialsExpiringWithin(now, in7d);
        long premiumTotal = orgRepo.countBySubscriptionTierAndParentOrganizationIsNull(SubscriptionTier.PREMIUM);
        long freeTotal = orgRepo.countBySubscriptionTierAndParentOrganizationIsNull(SubscriptionTier.FREE);
        long totalMembers = userRepo.count();

        long createdLast30d = auditRepo.countByActionTypeAndOccurredAtAfter(
                OrgAuditActions.ORGANIZATION_CREATED, minus30d);
        long suspendedLast7d = auditRepo.countByActionTypeAndOccurredAtAfter(
                OrgAuditActions.ORGANIZATION_SUSPENDED, minus7d);
        long invitedLast7d = auditRepo.countByActionTypeAndOccurredAtAfter(
                OrgAuditActions.MEMBER_INVITED, minus7d);

        BigDecimal retention = totalOrgs == 0 ? BigDecimal.ZERO :
                BigDecimal.valueOf(activeCount)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalOrgs), 2, RoundingMode.HALF_UP);

        KpiBlock kpis = new KpiBlock(
                totalOrgs, createdLast30d,
                activeCount, retention,
                suspendedCount, suspendedLast7d,
                trialsExpiring,
                totalMembers, invitedLast7d
        );

        // Tier mix: Trial slice = active trials; Premium = total Premium - active trials.
        TierMix mix = new TierMix(premiumTotal - onTrial, onTrial, freeTotal);

        List<AttentionItem> attention = attentionService.evaluate();

        return new DashboardResponse(kpis, attention, mix, now);
    }
}
