package com.bvisionry.gdpr.web;

import com.bvisionry.common.audit.AuditLogger;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.RateLimitExceededException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.gdpr.PersonalDataRepository;
import com.bvisionry.common.gdpr.PersonalDataRepository.AccountIdentity;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.gdpr.dto.DeleteAccountRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Self-service GDPR rights for the authenticated caller: Art. 15/20 access and
 * portability, and Art. 17 erasure.
 *
 * <p><strong>Tenant scoping.</strong> Both operations resolve their subject
 * from {@link CurrentUserAccessor} only. No endpoint here takes a user id, so
 * there is nothing to aim at another account — the strongest available form of
 * the mandatory scoping rule. This is a data-subject surface, never an admin
 * data-governance console; org-admin removal already lives on
 * {@code /api/organizations/{orgId}/members} and shares this class's erasure
 * component so the two can never diverge.
 */
@Service
@RequiredArgsConstructor
public class GdprService {

    private static final String ACTION_ACCOUNT_EXPORTED = "GDPR_ACCOUNT_EXPORTED";
    private static final String ACTION_ACCOUNT_DELETED = "GDPR_ACCOUNT_DELETED";
    private static final String ENTITY_USER = "User";

    private static final Duration EXPORT_WINDOW = Duration.ofHours(1);
    // ponytail: constants, not properties — nobody has asked to tune them, and a
    // whole-account dump is not something a person does five times an hour twice.
    private static final int MAX_EXPORTS_PER_WINDOW = 5;
    private static final Duration DELETE_WINDOW = Duration.ofMinutes(15);
    private static final int MAX_DELETE_ATTEMPTS = 5;

    private final CurrentUserAccessor currentUser;
    private final PersonalDataRepository personalData;
    private final AuditLogger auditLogger;
    private final PasswordEncoder passwordEncoder;

    // ponytail: in-process sliding window, mirroring RateLimitService's in-memory
    // fallback path. That service lives in the aiconfig FEATURE package, which this
    // slice may not import (ArchUnit ratchet), and a whole-account dump is not a hot
    // path — per-instance limiting is enough to stop a scripted exfil loop.
    // Ceiling: N instances = N x the limit, and one small deque per user who has
    // ever exported (bounded by user count, never evicted). Upgrade path is a
    // rate-limit port in common/ that both this and RateLimitService sit behind.
    private final Map<UUID, Deque<Instant>> exportWindows = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Instant>> deleteWindows = new ConcurrentHashMap<>();

    /**
     * Art. 15/20 — everything held about the caller, as one JSON document in a
     * stable, section-per-source shape. Read-only: {@link #recordExport} carries
     * the one write, in its own transaction after this one closes.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> exportMyData() {
        CurrentUser me = currentUser.require();
        requireAllowance(exportWindows, me.userId(), EXPORT_WINDOW, MAX_EXPORTS_PER_WINDOW,
                "Too many data exports from this account. Try again in an hour.");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exportedAt", Instant.now().toString());
        payload.put("userId", me.userId().toString());
        payload.put("data", personalData.exportFor(me.userId()));
        return payload;
    }

    /**
     * Art. 17 — erase the caller's account. Irreversible.
     *
     * <p>Two refusals, both about an organisation's integrity rather than about
     * the request itself, and both mirroring the existing admin removal guards:
     * a SUPER_ADMIN would strand the platform, and an org's last active
     * ORG_ADMIN would strand the organisation. Neither is a denial of the right
     * — both name the one step that unblocks it.
     */
    @Transactional
    public void deleteMyAccount(DeleteAccountRequest request) {
        CurrentUser me = currentUser.require();
        // Counted BEFORE any comparison and on attempts, not successes: the
        // password check below is a bcrypt oracle otherwise, and change-password
        // already meters the equivalent path.
        requireAllowance(deleteWindows, me.userId(), DELETE_WINDOW, MAX_DELETE_ATTEMPTS,
                "Too many account deletion attempts. Try again later.");
        AccountIdentity account = personalData.findAccount(me.userId());
        if (account == null) {
            throw new ResourceNotFoundException("User", me.userId().toString());
        }
        if (!account.email().equalsIgnoreCase(request.confirmEmail().trim())) {
            throw new BadRequestException("Type your email address exactly to confirm deletion.");
        }
        requireReauthentication(account, request.currentPassword());
        if ("SUPER_ADMIN".equals(me.role())) {
            throw new BadRequestException(
                    "Platform super admins cannot delete their own account here. "
                            + "Ask another super admin to remove it.");
        }
        if ("ORG_ADMIN".equals(me.role())
                && me.orgId() != null
                && personalData.isSoleActiveAdminOfTopLevelOrg(me.orgId())) {
            throw new BadRequestException(
                    "You are the only active Org Admin. Promote another member to Org Admin first, "
                            + "then delete your account.");
        }

        // Audited BEFORE the delete so the row exists regardless of flush order,
        // and with a null actor because the actor is about to stop existing —
        // audit_logs.actor_id would be SET NULL by the cascade anyway. entity_id
        // carries the (now meaningless) user id, which is the accountability
        // record without retaining the person's email.
        auditLogger.log(null, me.orgId(), ACTION_ACCOUNT_DELETED, ENTITY_USER, me.userId(),
                Map.of("initiatedBy", "SELF_SERVICE"));

        personalData.erasePersonalDataFor(me.userId());

        // ponytail: the authenticated principal is cached in-process for
        // bvisionry.auth.principal-cache-ttl-seconds (default 10s) and this native
        // delete bypasses its JPA eviction listener, so a stolen access token could
        // survive the deletion by up to that TTL. Bounded and documented by
        // UserPrincipalCache itself; the refresh token is gone with the row and the
        // web client logs out immediately. Close it properly by moving eviction
        // behind a port in common/ if that window ever matters.
        personalData.deleteUserRow(me.userId());
    }

    /**
     * The export's one write, called by the controller after {@link #exportMyData}
     * returns so the read itself can stay {@code readOnly} and its transaction is
     * already closed. A self-service dump of every record about a person is the
     * classic account-takeover exfiltration path, and the org's activity feed is
     * where that shows up.
     *
     * <p>Plain {@code REQUIRED} rather than {@code REQUIRES_NEW}: from the
     * (non-transactional) controller the effect in production is identical — a
     * short transaction of its own — while REQUIRES_NEW would additionally make
     * this unverifiable, because a suspended-and-restarted transaction cannot see
     * the uncommitted seed data of the repo's rollback-isolated integration tests
     * and the {@code actor_id} FK fails.
     */
    @Transactional
    public void recordExport() {
        CurrentUser me = currentUser.require();
        auditLogger.log(me.userId(), me.orgId(), ACTION_ACCOUNT_EXPORTED, ENTITY_USER, me.userId(), Map.of());
    }

    /**
     * Irreversible action, so it must not be reachable from a hijacked session on
     * weaker proof than a recoverable password change demands. Returns 400 rather
     * than 401 on a wrong password deliberately: the BFF proxy treats 401 as an
     * expired session and silently refreshes + retries, which would turn a typo
     * into an apparent logout.
     */
    private void requireReauthentication(AccountIdentity account, String currentPassword) {
        if (account.passwordHash() == null) {
            return; // SSO-only: no hash to verify, so email confirmation is the proof
        }
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new BadRequestException("Enter your current password to confirm deletion.");
        }
        if (!passwordEncoder.matches(currentPassword, account.passwordHash())) {
            throw new BadRequestException("Current password is incorrect.");
        }
    }

    private void requireAllowance(Map<UUID, Deque<Instant>> windows, UUID userId,
                                   Duration size, int max, String message) {
        Instant cutoff = Instant.now().minus(size);
        Deque<Instant> window = windows.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
                window.pollFirst();
            }
            if (window.size() >= max) {
                throw new RateLimitExceededException(message);
            }
            window.addLast(Instant.now());
        }
    }
}
