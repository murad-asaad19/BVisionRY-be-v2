package com.bvisionry.upgrade;

import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.RateLimitExceededException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.config.FrontendUrls;
import com.bvisionry.notification.EmailService;
import com.bvisionry.organization.OrgAuditActions;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.upgrade.dto.UpgradeRequestCreateRequest;
import com.bvisionry.upgrade.dto.UpgradeRequestResponse;
import com.bvisionry.upgrade.entity.UpgradeFeatureContext;
import com.bvisionry.upgrade.entity.UpgradeRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UpgradeRequestService {

    private final UpgradeRequestRepository requestRepo;
    private final UserRepository userRepo;
    private final UpgradeRequestRecipientService recipientService;
    private final UpgradePromptService promptService;
    private final EmailService emailService;
    private final AuditService auditService;
    private final FrontendUrls frontendUrls;

    private Duration cooldown() {
        return Duration.ofHours(promptService.get().cooldownHours());
    }

    @Transactional(readOnly = true)
    public Optional<UpgradeRequestResponse> findLatestForCurrentUser(UUID currentUserId) {
        return requestRepo.findFirstByRequestedBy_IdOrderByCreatedAtDesc(currentUserId)
                .map(r -> UpgradeRequestResponse.from(r, cooldown()));
    }

    @Transactional
    public UpgradeRequestResponse create(UUID currentUserId, UpgradeRequestCreateRequest req) {
        // Serialize concurrent requests from the same member before reading the
        // cooldown state. enforceCooldown() is a check-then-insert: without this,
        // two concurrent POSTs from one requester can both pass the read and both
        // insert, leaking a duplicate request + duplicate super-admin emails. The
        // transaction-scoped advisory lock makes the second POST wait until the
        // first commits, after which it sees the committed row and is rejected by
        // the normal time-window check. The lock auto-releases at commit/rollback.
        requestRepo.acquireRequesterLock(currentUserId.toString());

        User user = userRepo.findByIdWithOrganization(currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", currentUserId.toString()));

        // Billing lives on the ROOT org: a sub-org row is permanently FREE, so
        // eligibility, the request row, the audit entry, and the admin
        // dashboard link must all target the parent — the only org whose tier
        // a super admin can actually change.
        Organization org = user.getOrganization();
        if (org != null && org.isSubOrganization()) {
            org = org.getParentOrganization();
        }

        validateEligibility(user, org);
        Duration window = cooldown();
        enforceCooldown(currentUserId, window);
        UpgradeFeatureContext feature = req.featureContext() == null
                ? UpgradeFeatureContext.OTHER
                : req.featureContext();
        String note = (req.note() == null || req.note().isBlank()) ? null : req.note().trim();

        UpgradeRequest entity = new UpgradeRequest();
        entity.setOrganization(org);
        entity.setRequestedBy(user);
        entity.setFeatureContext(feature);
        entity.setNote(note);
        UpgradeRequest saved = requestRepo.save(entity);

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("featureContext", feature.label());
        auditDetails.put("memberName", user.getName());
        auditService.log(currentUserId, org.getId(), OrgAuditActions.UPGRADE_REQUESTED,
                OrgAuditActions.ENTITY_ORGANIZATION, org.getId(), auditDetails);

        notifyRecipients(user, org, feature, note);

        return UpgradeRequestResponse.from(saved, window);
    }

    /** {@code billingOrg} is the requester's ROOT org (the parent when they sit in a sub-org). */
    private void validateEligibility(User user, Organization billingOrg) {
        if (user.getRole() == UserRole.SUPER_ADMIN) {
            throw new BadRequestException("Super admins cannot request upgrades.");
        }
        if (billingOrg == null) {
            throw new BadRequestException("You must belong to an organization to request an upgrade.");
        }
        if (billingOrg.getSubscriptionTier() != SubscriptionTier.FREE) {
            throw new BadRequestException(
                    "Your organization is already on the " + billingOrg.getSubscriptionTier() + " plan.");
        }
    }

    private void enforceCooldown(UUID userId, Duration window) {
        requestRepo.findFirstByRequestedBy_IdOrderByCreatedAtDesc(userId).ifPresent(latest -> {
            Instant cooldownEndsAt = latest.getCreatedAt().plus(window);
            if (cooldownEndsAt.isAfter(Instant.now())) {
                throw new RateLimitExceededException(
                        "You already requested an upgrade. You can ask again after "
                                + cooldownEndsAt + ".");
            }
        });
    }

    private void notifyRecipients(User user, Organization org,
                                   UpgradeFeatureContext feature, String note) {
        var recipients = recipientService.resolveRecipients();
        if (recipients.isEmpty()) {
            log.warn("Upgrade request {} for org {} has no email recipients — set "
                            + "notifications.upgrade_request_recipients or create a SUPER_ADMIN user.",
                    user.getId(), org.getId());
            return;
        }
        String dashboardUrl = frontendUrls.path("/admin/organizations/" + org.getId());
        for (String to : recipients) {
            emailService.sendUpgradeRequestedAsync(
                    to, org.getName(), user.getName(), user.getEmail(),
                    feature.label(), note, dashboardUrl);
        }
    }
}
