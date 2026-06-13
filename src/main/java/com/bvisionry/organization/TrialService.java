package com.bvisionry.organization;

import com.bvisionry.audit.AuditRepository;
import com.bvisionry.audit.AuditService;
import com.bvisionry.audit.entity.AuditLog;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.tx.AfterCommit;
import com.bvisionry.notification.EmailService;
import com.bvisionry.organization.dto.OrganizationResponse;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.reporting.service.CacheInvalidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrialService {

    /** Threshold (days) for sending the trial-ending heads-up email. */
    static final int TRIAL_ENDING_SOON_DAYS = 3;

    private final OrganizationRepository orgRepo;
    private final UserRepository userRepo;
    private final AuditService auditService;
    private final AuditRepository auditRepo;
    private final EmailService emailService;
    private final CacheInvalidationService cacheInvalidationService;
    private final PlatformTransactionManager transactionManager;

    /**
     * REQUIRES_NEW template used by {@link #expireLapsed()} so each org commits in its
     * own transaction. A self-invoked {@code @Transactional} method would be bypassed
     * by the Spring proxy and share the caller's transaction, defeating the isolation.
     */
    private TransactionTemplate requiresNewTx;

    @Value("${bvisionry.frontend.base-url:http://localhost:5173}")
    private String frontendBaseUrl;

    @PostConstruct
    void initTransactionTemplate() {
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public OrganizationResponse startTrial(UUID orgId, int durationDays, UUID actorId) {
        Organization org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgId.toString()));

        if (!org.isActive()) {
            throw new BadRequestException("Reactivate the organization before starting a trial.");
        }
        if (org.isOnTrial()) {
            throw new BadRequestException("Organization is already on an active trial. Use Extend instead.");
        }
        if (org.getSubscriptionTier() == SubscriptionTier.PREMIUM) {
            throw new BadRequestException("Organization is already on Premium. No trial needed.");
        }

        Instant endsAt = Instant.now().plus(durationDays, ChronoUnit.DAYS);
        org.setSubscriptionTier(SubscriptionTier.PREMIUM);
        org.setTrialEndsAt(endsAt);
        Organization saved = orgRepo.save(org);

        Map<String, Object> details = new HashMap<>();
        details.put("durationDays", durationDays);
        details.put("endsAt", endsAt.toString());
        auditService.log(actorId, saved.getId(), OrgAuditActions.TRIAL_STARTED,
                OrgAuditActions.ENTITY_ORGANIZATION, saved.getId(), details);
        AfterCommit.run(cacheInvalidationService::invalidateOnTierChange);

        return toResponse(saved);
    }

    @Transactional
    public OrganizationResponse extendTrial(UUID orgId, int additionalDays, UUID actorId) {
        Organization org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgId.toString()));

        if (!org.isOnTrial()) {
            throw new BadRequestException("Organization has no active trial.");
        }

        Instant oldEnd = org.getTrialEndsAt();
        Instant newEnd = oldEnd.plus(additionalDays, ChronoUnit.DAYS);
        org.setTrialEndsAt(newEnd);
        Organization saved = orgRepo.save(org);

        Map<String, Object> details = new HashMap<>();
        details.put("additionalDays", additionalDays);
        details.put("oldEndsAt", oldEnd.toString());
        details.put("newEndsAt", newEnd.toString());
        auditService.log(actorId, saved.getId(), OrgAuditActions.TRIAL_EXTENDED,
                OrgAuditActions.ENTITY_ORGANIZATION, saved.getId(), details);
        AfterCommit.run(cacheInvalidationService::invalidateOnTierChange);

        return toResponse(saved);
    }

    @Transactional
    public OrganizationResponse endTrialEarly(UUID orgId, UUID actorId) {
        Organization org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgId.toString()));

        if (!org.isOnTrial()) {
            throw new BadRequestException("Organization has no active trial.");
        }

        Instant plannedEnd = org.getTrialEndsAt();
        Instant now = Instant.now();
        org.setSubscriptionTier(SubscriptionTier.FREE);
        org.setTrialEndsAt(now);
        Organization saved = orgRepo.save(org);

        Map<String, Object> details = new HashMap<>();
        details.put("plannedEndsAt", plannedEnd.toString());
        auditService.log(actorId, saved.getId(), OrgAuditActions.TRIAL_ENDED_EARLY,
                OrgAuditActions.ENTITY_ORGANIZATION, saved.getId(), details);
        AfterCommit.run(cacheInvalidationService::invalidateOnTierChange);

        return toResponse(saved);
    }

    /**
     * Expires every lapsed trial. Intentionally NOT {@code @Transactional} over the whole
     * batch: each org is processed in its own REQUIRES_NEW transaction so a failure on one
     * org (e.g. a synchronous email/audit error) only rolls back that org, leaving the rest
     * committed instead of discarding the entire batch.
     */
    public List<UUID> expireLapsed() {
        List<Organization> lapsed = orgRepo.findLapsedTrials(Instant.now());
        if (lapsed.isEmpty()) return List.of();

        List<UUID> expired = new ArrayList<>();
        for (Organization lapsedOrg : lapsed) {
            UUID orgId = lapsedOrg.getId();
            try {
                requiresNewTx.executeWithoutResult(status -> expireOne(orgId));
                expired.add(orgId);
            } catch (RuntimeException ex) {
                log.error("Failed to expire lapsed trial for org={} — continuing with remaining orgs",
                        orgId, ex);
            }
        }

        if (!expired.isEmpty()) {
            // Tier changed for at least one org; refresh tier-derived caches once.
            cacheInvalidationService.invalidateOnTierChange();
        }
        return expired;
    }

    /**
     * Expires a single lapsed trial inside its own transaction. Re-reads the org by id so
     * the entity is managed by the current (REQUIRES_NEW) transaction rather than detached
     * from the read that built the batch.
     */
    void expireOne(UUID orgId) {
        Organization org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgId.toString()));
        org.setSubscriptionTier(SubscriptionTier.FREE);
        orgRepo.save(org);
        Map<String, Object> details = new HashMap<>();
        details.put("trialEndsAt", org.getTrialEndsAt() == null ? null : org.getTrialEndsAt().toString());
        auditService.log(null, org.getId(), OrgAuditActions.TRIAL_EXPIRED,
                OrgAuditActions.ENTITY_ORGANIZATION, org.getId(), details);
        notifyTrialExpired(org);
    }

    /**
     * Sends the heads-up email to ORG_ADMINs of every org whose trial ends within the
     * {@link #TRIAL_ENDING_SOON_DAYS}-day threshold. Idempotent across scheduler ticks
     * via a {@code TRIAL_ENDING_SOON_NOTIFIED} audit-log row tied to this trial cycle.
     */
    @Transactional
    public List<UUID> notifyEndingTrials() {
        Instant now = Instant.now();
        Instant cutoff = now.plus(TRIAL_ENDING_SOON_DAYS, ChronoUnit.DAYS);
        List<Organization> ending = orgRepo.findEndingTrialsWithin(now, cutoff);
        if (ending.isEmpty()) return List.of();

        List<UUID> notified = new java.util.ArrayList<>();
        for (Organization org : ending) {
            if (alreadyNotifiedThisCycle(org)) continue;

            int daysLeft = (int) Math.max(1, Duration.between(now, org.getTrialEndsAt()).toDays());
            List<User> recipients = recipientsFor(org.getId());
            if (recipients.isEmpty()) {
                log.warn("No recipients found for trial-ending-soon notification, org={} ({})",
                        org.getId(), org.getName());
                continue;
            }

            String dashboardUrl = frontendBaseUrl + "/admin/organizations/" + org.getId();
            for (User u : recipients) {
                emailService.sendTrialEndingSoonAsync(
                        u.getEmail(), org.getName(), daysLeft, org.getTrialEndsAt(), dashboardUrl);
            }

            Map<String, Object> details = new HashMap<>();
            details.put("trialEndsAt", org.getTrialEndsAt().toString());
            details.put("daysLeft", daysLeft);
            details.put("recipientCount", recipients.size());
            auditService.log(null, org.getId(), OrgAuditActions.TRIAL_ENDING_SOON_NOTIFIED,
                    OrgAuditActions.ENTITY_ORGANIZATION, org.getId(), details);
            notified.add(org.getId());
        }
        return notified;
    }

    /**
     * Skip if a {@code TRIAL_ENDING_SOON_NOTIFIED} audit row exists with a timestamp
     * that falls within the current trial cycle (i.e. after trialEndsAt - 4 days).
     * Older audit rows belong to a previous trial that was extended/restarted.
     */
    private boolean alreadyNotifiedThisCycle(Organization org) {
        AuditLog last = auditRepo.findFirstByEntityTypeAndEntityIdAndActionTypeOrderByOccurredAtDesc(
                OrgAuditActions.ENTITY_ORGANIZATION,
                org.getId(),
                OrgAuditActions.TRIAL_ENDING_SOON_NOTIFIED);
        if (last == null || last.getOccurredAt() == null) return false;
        Instant cycleStart = org.getTrialEndsAt().minus(TRIAL_ENDING_SOON_DAYS + 1, ChronoUnit.DAYS);
        return last.getOccurredAt().isAfter(cycleStart);
    }

    private void notifyTrialExpired(Organization org) {
        List<User> recipients = recipientsFor(org.getId());
        if (recipients.isEmpty()) {
            log.warn("No recipients found for trial-expired notification, org={} ({})",
                    org.getId(), org.getName());
            return;
        }
        String dashboardUrl = frontendBaseUrl + "/admin/organizations/" + org.getId();
        Instant expiredAt = org.getTrialEndsAt();
        for (User u : recipients) {
            emailService.sendTrialExpiredAsync(u.getEmail(), org.getName(), expiredAt, dashboardUrl);
        }
    }

    /**
     * ORG_ADMIN members preferred; falls back to all org members so a misconfigured
     * org without a designated admin still gets the notification somewhere.
     */
    private List<User> recipientsFor(UUID orgId) {
        List<User> all = userRepo.findByOrganizationId(orgId);
        List<User> admins = all.stream()
                .filter(u -> u.getRole() == UserRole.ORG_ADMIN)
                .filter(u -> u.getEmail() != null && !u.getEmail().isBlank())
                .toList();
        if (!admins.isEmpty()) return admins;
        return all.stream()
                .filter(u -> u.getEmail() != null && !u.getEmail().isBlank())
                .toList();
    }

    OrganizationResponse toResponse(Organization org) {
        long memberCount = userRepo.countByOrganizationId(org.getId());
        Instant lastActive = userRepo.findMaxLastLoginByOrganizationId(org.getId());
        return OrganizationResponse.from(org, memberCount, lastActive);
    }
}
