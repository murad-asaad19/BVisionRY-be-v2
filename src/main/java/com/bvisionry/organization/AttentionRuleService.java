package com.bvisionry.organization;

import com.bvisionry.audit.AuditRepository;
import com.bvisionry.audit.entity.AuditLog;
import com.bvisionry.common.enums.AttentionSeverity;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.organization.dto.AttentionItem;
import com.bvisionry.organization.entity.Organization;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttentionRuleService {

    private final OrganizationRepository orgRepo;
    private final AuditRepository auditRepo;
    private final AttentionThresholdsService thresholds;

    /** Per-org aggregate row used by the rule evaluator — avoids N lookups. */
    private record OrgStats(long memberCount, Instant lastLogin) {}

    public List<AttentionItem> evaluate() {
        Instant now = Instant.now();
        // Snapshot once per evaluation so all rules see consistent thresholds even
        // if an admin updates them mid-call.
        int suspendedDays                 = thresholds.suspendedDays();
        int trialExpiryWindowDays         = thresholds.trialExpiryWindowDays();
        int trialJustExpiredWindowDays    = thresholds.trialJustExpiredWindowDays();
        int idleDays                      = thresholds.idleDays();
        int onboardingStalledHours        = thresholds.onboardingStalledHours();

        Instant suspendedCutoff       = now.minus(suspendedDays, ChronoUnit.DAYS);
        Instant trialExpiryCutoff     = now.plus(trialExpiryWindowDays, ChronoUnit.DAYS);
        Instant trialJustExpiredCutoff = now.minus(trialJustExpiredWindowDays, ChronoUnit.DAYS);
        Instant idleCutoff            = now.minus(idleDays, ChronoUnit.DAYS);
        Instant onboardingCutoff      = now.minus(onboardingStalledHours, ChronoUnit.HOURS);

        // Single aggregate query for all orgs' member-count + last-login —
        // replaces the previous up-to-4-queries-per-org fan-out.
        Map<UUID, OrgStats> statsById = new HashMap<>();
        for (Object[] row : orgRepo.findOrgStatsAll()) {
            statsById.put((UUID) row[0], new OrgStats((Long) row[1], (Instant) row[2]));
        }

        List<AttentionItem> items = new ArrayList<>();
        for (Organization o : orgRepo.findAll()) {
            // 1. Suspended too long
            if (!o.isActive()) {
                AuditLog suspendEvt = auditRepo
                        .findFirstByEntityTypeAndEntityIdAndActionTypeOrderByOccurredAtDesc(
                                OrgAuditActions.ENTITY_ORGANIZATION, o.getId(),
                                OrgAuditActions.ORGANIZATION_SUSPENDED);
                // Fall back to createdAt — NOT updatedAt — when no suspension audit
                // exists. updatedAt is bumped by any field change (tier, name,
                // description), which silently hid long-suspended orgs that had
                // unrelated edits. createdAt is a conservative lower bound: if the
                // org is older than the threshold and is currently suspended, we
                // surface it.
                Instant since = suspendEvt != null ? suspendEvt.getOccurredAt() : o.getCreatedAt();
                if (since != null && since.isBefore(suspendedCutoff)) {
                    items.add(new AttentionItem("SUSPENDED_TOO_LONG", AttentionSeverity.CRITICAL,
                            o.getId(), o.getName(),
                            "Suspended for over " + pluralDays(suspendedDays),
                            "Decide: reinstate, downgrade, or close.",
                            "Resolve →", since));
                }
                continue;
            }

            // 2. Trial expiring (only if active)
            if (o.getSubscriptionTier() == SubscriptionTier.PREMIUM
                    && o.getTrialEndsAt() != null
                    && o.getTrialEndsAt().isAfter(now)
                    && o.getTrialEndsAt().isBefore(trialExpiryCutoff)) {
                long daysUntil = ChronoUnit.DAYS.between(now, o.getTrialEndsAt());
                items.add(new AttentionItem("TRIAL_EXPIRING", AttentionSeverity.WARNING,
                        o.getId(), o.getName(),
                        "Trial ends in " + pluralDays(daysUntil),
                        "Strong upgrade signal — send conversion outreach.",
                        "Outreach →", o.getTrialEndsAt()));
            }

            // 3. Trial just expired (tier=FREE and TRIAL_EXPIRED audit recently)
            if (o.getSubscriptionTier() == SubscriptionTier.FREE) {
                AuditLog expiredEvt = auditRepo
                        .findFirstByEntityTypeAndEntityIdAndActionTypeOrderByOccurredAtDesc(
                                OrgAuditActions.ENTITY_ORGANIZATION, o.getId(),
                                OrgAuditActions.TRIAL_EXPIRED);
                if (expiredEvt != null && expiredEvt.getOccurredAt().isAfter(trialJustExpiredCutoff)) {
                    long daysSince = ChronoUnit.DAYS.between(expiredEvt.getOccurredAt(), now);
                    items.add(new AttentionItem("TRIAL_JUST_EXPIRED", AttentionSeverity.INFO,
                            o.getId(), o.getName(),
                            "Trial expired " + pluralDays(daysSince) + " ago",
                            "Re-engagement opportunity.",
                            "Re-engage →", expiredEvt.getOccurredAt()));
                }
            }

            OrgStats stats = statsById.getOrDefault(o.getId(), new OrgStats(0L, null));
            long memberCount = stats.memberCount();

            // 4. Idle (active, has members, MAX(last_login) old, NOT on trial)
            // Trial orgs are intentionally excluded — they're in evaluation, not
            // churning. The TRIAL_EXPIRING rule above is the correct signal for
            // trial-period attention.
            //
            // When lastLogin is null (no member has ever logged in), the org isn't
            // strictly "idle" — it has never been active. We anchor on createdAt so
            // a brand-new org doesn't immediately misreport as "Idle for N days",
            // and we use a different headline / detail to reflect the real state.
            if (memberCount >= 1 && !o.isOnTrial()) {
                Instant lastActive = stats.lastLogin();
                boolean neverLoggedIn = lastActive == null;
                Instant reference = neverLoggedIn ? o.getCreatedAt() : lastActive;
                if (reference != null && reference.isBefore(idleCutoff)) {
                    String headline = neverLoggedIn
                            ? "No member has logged in yet"
                            : "Idle for over " + pluralDays(idleDays);
                    String detail = neverLoggedIn
                            ? "Onboarding may have stalled — check in with the admin."
                            : "Possible churn — check in.";
                    items.add(new AttentionItem("IDLE_ORG", AttentionSeverity.INFO,
                            o.getId(), o.getName(),
                            headline, detail,
                            "Investigate →", reference));
                }
            }

            // 5. Onboarding stalled (created < onboardingStalledHours ago, 0 members)
            if (memberCount == 0 && o.getCreatedAt() != null && o.getCreatedAt().isAfter(onboardingCutoff)) {
                items.add(new AttentionItem("ONBOARDING_STALLED", AttentionSeverity.INFO,
                        o.getId(), o.getName(),
                        "New org with no members yet",
                        "Help admin invite teammates.",
                        "Help onboard →", o.getCreatedAt()));
            }
        }

        // AttentionSeverity declares CRITICAL, WARNING, INFO in that order, so
        // ordinal() naturally ranks most-urgent first.
        items.sort(Comparator
                .comparingInt((AttentionItem i) -> i.severity().ordinal())
                .thenComparing(AttentionItem::since, Comparator.nullsLast(Comparator.reverseOrder())));
        return items;
    }

    private static String pluralDays(long n) {
        return n + (n == 1 ? " day" : " days");
    }
}
