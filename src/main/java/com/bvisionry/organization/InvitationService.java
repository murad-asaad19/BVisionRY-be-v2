package com.bvisionry.organization;

import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.AuthService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.dto.AuthResponse;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.tx.AfterCommit;
import com.bvisionry.notification.EmailService;
import com.bvisionry.organization.dto.AcceptInvitationRequest;
import com.bvisionry.organization.dto.InvitationAttemptResponse;
import com.bvisionry.organization.dto.InvitationAttemptSummary;
import com.bvisionry.organization.dto.InvitationResponse;
import com.bvisionry.organization.dto.InviteMembersRequest;
import com.bvisionry.organization.entity.Invitation;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.organization.event.MemberJoinedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvitationService {

    private static final int INVITATION_EXPIRY_DAYS = 7;

    private final InvitationRepository invitationRepository;
    private final InvitationAcceptanceAttemptRepository attemptRepository;
    private final InvitationAttemptService invitationAttemptService;
    private final OrganizationService organizationService;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final ApplicationEventPublisher eventPublisher;

    @org.springframework.beans.factory.annotation.Value("${bvisionry.frontend.base-url:http://localhost:5173}")
    private String frontendBaseUrl;

    @Transactional
    public List<InvitationResponse> inviteMembers(UUID orgId, InviteMembersRequest request) {
        Organization org = organizationService.findActiveOrThrow(orgId);
        List<InvitationResponse> responses = new ArrayList<>();

        // Hoist the inviter lookup out of the loop — same value for every recipient.
        String inviterName = userRepository.findById(request.invitedBy())
                .map(User::getName).orElse("An administrator");

        for (String email : request.emails()) {
            String normalizedEmail = email.toLowerCase().trim();
            if (userRepository.existsByEmailAndOrganizationId(normalizedEmail, orgId)) continue;
            if (invitationRepository.existsByEmailAndOrganizationIdAndStatus(
                    normalizedEmail, orgId, Invitation.InvitationStatus.PENDING)) continue;

            Invitation invitation = new Invitation();
            invitation.setEmail(normalizedEmail);
            invitation.setOrganization(org);
            invitation.setRole(request.role());
            invitation.setInvitedBy(request.invitedBy());
            invitation.setExpiresAt(Instant.now().plus(INVITATION_EXPIRY_DAYS, ChronoUnit.DAYS));

            Invitation saved = invitationRepository.save(invitation);
            String acceptUrl = frontendBaseUrl + "/invitations/" + saved.getToken();
            // Fire-and-forget on the emailExecutor pool: SMTP latency must not
            // hold row locks for the surrounding @Transactional method.
            emailService.sendInvitationEmailAsync(normalizedEmail, org.getName(), acceptUrl,
                    saved.getExpiresAt(), inviterName);

            auditService.log(request.invitedBy(), orgId, OrgAuditActions.MEMBER_INVITED,
                    OrgAuditActions.ENTITY_ORGANIZATION, orgId,
                    Map.of("email", normalizedEmail, "invitationId", saved.getId().toString(),
                            "role", request.role().name()));
            responses.add(InvitationResponse.from(saved));
        }
        return responses;
    }

    @Transactional
    public InvitationResponse acceptInvitation(UUID token) {
        Invitation invitation = invitationRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation", token.toString()));
        if (!invitation.isAcceptable()) {
            throw new BadRequestException("Invitation is no longer valid");
        }

        User existing = userRepository.findByEmail(invitation.getEmail()).orElse(null);
        boolean isNewMembership = existing == null
                || existing.getOrganization() == null;
        if (existing != null) {
            requireInvitationBindable(existing, invitation);
        }
        User user = existing != null ? existing : newUserFor(invitation);

        applyMembership(user, invitation);
        User savedUser = userRepository.save(user);

        invitation.setStatus(Invitation.InvitationStatus.ACCEPTED);
        invitation.setAcceptedAt(Instant.now());
        invitationRepository.save(invitation);

        // Fire only on a genuinely new membership — re-accepts by an existing
        // member of the same org would otherwise re-trigger auto-assign rules
        // and materialise duplicate assignments. AFTER_COMMIT phase ensures the
        // membership write is durably committed before listeners fan out.
        if (isNewMembership) {
            eventPublisher.publishEvent(new MemberJoinedEvent(
                    invitation.getOrganization().getId(), savedUser.getId(), savedUser.getUserType()));
        }

        return InvitationResponse.from(invitation);
    }

    @Transactional(readOnly = true)
    public InvitationResponse getInvitationByToken(UUID token) {
        Invitation invitation = invitationRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation", token.toString()));
        // Bump view counters in a separate (REQUIRES_NEW) transaction so this
        // read-only path stays read-only from JPA's perspective and so the
        // counter survives even if a downstream caller's tx rolls back.
        // Only count views of pending invites — once accepted/expired/revoked,
        // bumping is just noise.
        if (invitation.getStatus() == Invitation.InvitationStatus.PENDING) {
            invitationAttemptService.recordView(invitation.getId());
        }
        return InvitationResponse.from(invitation);
    }

    @Transactional
    public AuthResponse acceptInvitationWithRegistration(UUID token, AcceptInvitationRequest request) {
        // ResourceNotFoundException for a bogus token propagates without attempt
        // recording — there's nothing to log against.
        Invitation invitation = invitationRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation", token.toString()));
        UUID invitationId = invitation.getId();
        try {
            if (invitation.getStatus() == Invitation.InvitationStatus.ACCEPTED) {
                throw new BadRequestException("Invitation has already been accepted");
            }
            if (!invitation.isAcceptable()) {
                throw new BadRequestException("Invitation is expired or no longer valid");
            }

            User existing = userRepository.findByEmail(invitation.getEmail()).orElse(null);
            boolean isNewMembership = existing == null
                    || existing.getOrganization() == null;
            if (existing != null) {
                requireInvitationBindable(existing, invitation);
            }
            User user = existing != null ? existing : newUserForRegistration(invitation);

            if (request != null && request.name() != null) {
                user.setName(request.name());
            } else if (user.getName() == null) {
                user.setName(invitation.getEmail().split("@")[0]);
            }
            // Only set a password on a brand-new / passwordless account. An
            // existing account (e.g. a self-registered, org-less user with a
            // real credential) MUST keep its hash — overwriting it here would
            // let anyone holding a stale invite token reset that user's
            // password and take over the account.
            if (request != null && request.password() != null && user.getPasswordHash() == null) {
                user.setPasswordHash(passwordEncoder.encode(request.password()));
            }

            applyMembership(user, invitation);
            User savedUser = userRepository.save(user);

            invitation.setStatus(Invitation.InvitationStatus.ACCEPTED);
            invitation.setAcceptedAt(Instant.now());
            invitationRepository.save(invitation);

            if (isNewMembership) {
                eventPublisher.publishEvent(new MemberJoinedEvent(
                        invitation.getOrganization().getId(), savedUser.getId(), savedUser.getUserType()));
            }

            // Mint a session so the invitee is immediately logged in. AuthService
            // persists the refresh-token row, enabling rotation/revocation later.
            AuthResponse response = authService.issueSession(savedUser, null);
            // Defer success recording until after the outer transaction commits;
            // failure recording uses REQUIRES_NEW so it survives the rollback below.
            AfterCommit.run(() -> invitationAttemptService.recordSuccess(invitationId));
            return response;
        } catch (RuntimeException ex) {
            invitationAttemptService.recordFailure(invitationId,
                    ex.getClass().getSimpleName(), ex.getMessage());
            throw ex;
        }
    }

    /**
     * Gate which existing accounts may be bound to an invitation.
     *
     * <p>SUPER_ADMIN is a platform-level (org-less) account. Allowing it to
     * accept an org invitation would have {@link #applyMembership} force it into
     * the inviting org with the invite's role, and
     * {@code acceptInvitationWithRegistration} would overwrite its password hash —
     * i.e. anyone who can send an invite to the super-admin's email could seize
     * the platform account. Reject outright.
     *
     * <p>An account already bound to a <em>different</em> organization is also
     * rejected: silently re-binding it would move historical data to a new tenant
     * and let the new org's auto-assign rules see a userType / role defined under
     * the previous org's taxonomy.
     */
    private static void requireInvitationBindable(User existing, Invitation invitation) {
        if (existing.getRole() == UserRole.SUPER_ADMIN) {
            throw new BadRequestException(
                    "This is a platform account and cannot accept organization invitations.");
        }
        if (existing.getOrganization() != null
                && !existing.getOrganization().getId().equals(invitation.getOrganization().getId())) {
            throw new BadRequestException(
                    "This account is already a member of another organization. "
                            + "Sign out of the existing organization before accepting this invitation.");
        }
    }

    private static User newUserFor(Invitation invitation) {
        User newUser = new User();
        newUser.setEmail(invitation.getEmail());
        newUser.setName(invitation.getEmail().split("@")[0]);
        newUser.setRole(invitation.getRole());
        newUser.setStatus(UserStatus.ACTIVE);
        newUser.setActivatedAt(Instant.now());
        return newUser;
    }

    private static User newUserForRegistration(Invitation invitation) {
        User newUser = new User();
        newUser.setEmail(invitation.getEmail());
        newUser.setStatus(UserStatus.ACTIVE);
        newUser.setActivatedAt(Instant.now());
        return newUser;
    }

    private static void applyMembership(User user, Invitation invitation) {
        // Only bind org + role for a genuinely new membership (new account or an
        // org-less self-registered user). For someone already in this org, treat
        // the accept as idempotent and DO NOT touch their role: a stale pending
        // invite (e.g. an ORG_ADMIN invite issued before the user joined as a
        // MEMBER) must not silently escalate their existing privileges. The
        // caller still marks the invitation ACCEPTED.
        boolean newMembership = user.getOrganization() == null;
        if (newMembership) {
            user.setOrganization(invitation.getOrganization());
            user.setRole(invitation.getRole());
            user.setInvitedBy(invitation.getInvitedBy());
            user.setInvitedAt(invitation.getCreatedAt());
        }
        user.setStatus(UserStatus.ACTIVE);
        if (user.getActivatedAt() == null) user.setActivatedAt(Instant.now());
    }

    @Transactional
    public void revokeInvitation(UUID orgId, UUID invitationId) {
        Invitation invitation = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation", invitationId.toString()));
        if (!invitation.getOrganization().getId().equals(orgId)) {
            throw new BadRequestException("Invitation does not belong to this organization");
        }
        if (invitation.getStatus() != Invitation.InvitationStatus.PENDING) {
            throw new BadRequestException("Only pending invitations can be revoked");
        }
        invitation.setStatus(Invitation.InvitationStatus.REVOKED);
        invitationRepository.save(invitation);
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> listByOrganization(UUID orgId) {
        organizationService.findActiveOrThrow(orgId);
        List<Invitation> invitations = invitationRepository.findByOrganizationId(orgId);
        if (invitations.isEmpty()) return List.of();

        // Bulk-fetch attempt summaries via a single grouped query — the alternative
        // is N+1 over the attempts table, which gets nasty for orgs with long
        // invitation histories.
        List<UUID> ids = invitations.stream().map(Invitation::getId).toList();
        Map<UUID, InvitationAttemptSummary> byId = new HashMap<>();
        for (InvitationAttemptSummary s : attemptRepository.summarize(ids)) {
            byId.put(s.invitationId(), s);
        }
        return invitations.stream()
                .map(inv -> InvitationResponse.from(inv,
                        byId.getOrDefault(inv.getId(), InvitationAttemptSummary.empty(inv.getId()))))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InvitationAttemptResponse> listAttempts(UUID orgId, UUID invitationId) {
        Invitation invitation = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation", invitationId.toString()));
        if (!invitation.getOrganization().getId().equals(orgId)) {
            throw new BadRequestException("Invitation does not belong to this organization");
        }
        return attemptRepository.findByInvitationIdOrderByAttemptedAtDesc(invitationId).stream()
                .map(InvitationAttemptResponse::from)
                .toList();
    }

}
