package com.bvisionry.organization;

import com.bvisionry.organization.entity.Invitation;
import com.bvisionry.organization.entity.InvitationAcceptanceAttempt;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Records every attempt to redeem an invitation token, regardless of whether
 * the surrounding accept-flow transaction commits or rolls back. The
 * {@link Propagation#REQUIRES_NEW} on the write methods is load-bearing: it
 * suspends the caller's transaction so a failed accept doesn't take the
 * forensic row down with it. Without that, "user tried 5 times and failed"
 * leaves zero trace in the database.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InvitationAttemptService {

    private static final int ERROR_CODE_MAX = 64;
    private static final int ERROR_MESSAGE_MAX = 500;

    private final InvitationRepository invitationRepository;
    private final InvitationAcceptanceAttemptRepository attemptRepository;
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(UUID invitationId) {
        record(invitationId, true, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID invitationId, String errorCode, String errorMessage) {
        record(invitationId, false, errorCode, errorMessage);
    }

    /**
     * Bumps view counters in their own transaction so a public token-lookup
     * can be made non-readonly on demand. Idempotent: callers may invoke
     * once per request without coordinating with other reads.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordView(UUID invitationId) {
        invitationRepository.findById(invitationId).ifPresent(inv -> {
            Instant now = Instant.now();
            if (inv.getFirstViewedAt() == null) inv.setFirstViewedAt(now);
            inv.setLastViewedAt(now);
            inv.setViewCount(inv.getViewCount() + 1);
            invitationRepository.save(inv);
        });
    }

    private void record(UUID invitationId, boolean success, String errorCode, String errorMessage) {
        InvitationAcceptanceAttempt attempt = new InvitationAcceptanceAttempt();
        attempt.setInvitation(entityManager.getReference(Invitation.class, invitationId));
        attempt.setAttemptedAt(Instant.now());
        attempt.setSuccess(success);
        attempt.setErrorCode(truncate(errorCode, ERROR_CODE_MAX));
        attempt.setErrorMessage(truncate(errorMessage, ERROR_MESSAGE_MAX));
        try {
            attemptRepository.save(attempt);
        } catch (DataIntegrityViolationException ex) {
            // Invitation row may have been deleted between the controller call
            // and this post-rollback log write; FK insert then fails. Don't
            // blow up the caller's flow.
            log.warn("Cannot record acceptance attempt for missing invitation {}", invitationId);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
