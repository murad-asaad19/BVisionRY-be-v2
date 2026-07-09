package com.bvisionry.organization;

import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.AuthService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.dto.AuthResponse;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.event.WorkshopEvents;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.config.FrontendUrls;
import com.bvisionry.organization.dto.AcceptJoinLinkRequest;
import com.bvisionry.organization.dto.JoinLinkInfoResponse;
import com.bvisionry.organization.dto.JoinLinkResponse;
import com.bvisionry.organization.entity.JoinLink;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.organization.event.MemberJoinedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JoinLinkService {

    private final JoinLinkRepository joinLinkRepository;
    private final OrganizationService organizationService;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final ApplicationEventPublisher eventPublisher;
    private final FrontendUrls frontendUrls;

    @Transactional
    public JoinLinkResponse generate(UUID orgId, int expiryDays, UUID workshopId, UUID createdBy) {
        Organization org = organizationService.findActiveOrThrow(orgId);

        // Join links provision MEMBER accounts, and members live in sub-orgs
        // only — a root org has no member population to join.
        if (!org.isSubOrganization()) {
            throw new BadRequestException(
                    "Join links are generated for a sub-organization. Members join sub-organizations.");
        }

        // A workshop link may only be bound to a workshop of this org — otherwise
        // an org admin could mint a link that enrols joiners into another org's
        // workshop team (the accept flow trusts the link's workshop_id).
        if (workshopId != null && !joinLinkRepository.workshopBelongsToOrg(orgId, workshopId)) {
            throw new ResourceNotFoundException("Workshop", workshopId.toString());
        }

        // Deactivate any existing active link in the same scope (org-wide, or
        // this workshop). Must FLUSH the deactivation before inserting the new
        // link: within one transaction Hibernate orders INSERTs before UPDATEs,
        // so a plain save() would insert the new active row while the old one is
        // still active and violate the partial unique constraint (one active
        // link per org / per workshop). saveAndFlush forces the UPDATE to hit
        // the DB first.
        findActiveLink(orgId, workshopId)
                .ifPresent(existing -> {
                    existing.setActive(false);
                    joinLinkRepository.saveAndFlush(existing);
                });

        JoinLink link = new JoinLink();
        link.setOrganization(org);
        link.setWorkshopId(workshopId);
        link.setExpiresAt(Instant.now().plus(expiryDays, ChronoUnit.DAYS));
        link.setCreatedBy(createdBy);
        JoinLink saved = joinLinkRepository.save(link);

        auditService.log(createdBy, orgId, "LINK_GENERATED", "JoinLink", saved.getId(),
                workshopId == null
                        ? Map.of("expiryDays", String.valueOf(expiryDays))
                        : Map.of("expiryDays", String.valueOf(expiryDays),
                                "workshopId", workshopId.toString()));

        return JoinLinkResponse.from(saved);
    }

    private Optional<JoinLink> findActiveLink(UUID orgId, UUID workshopId) {
        return workshopId == null
                ? joinLinkRepository.findByOrganizationIdAndWorkshopIdIsNullAndActiveTrue(orgId)
                : joinLinkRepository.findByOrganizationIdAndWorkshopIdAndActiveTrue(orgId, workshopId);
    }

    @Transactional(readOnly = true)
    public Optional<JoinLinkResponse> getActive(UUID orgId, UUID workshopId) {
        return findActiveLink(orgId, workshopId)
                .filter(link -> !link.isExpired())
                .map(JoinLinkResponse::from);
    }

    @Transactional
    public void revoke(UUID orgId, UUID workshopId, UUID actorId) {
        JoinLink link = findActiveLink(orgId, workshopId)
                .orElseThrow(() -> new ResourceNotFoundException("JoinLink", "active link for org " + orgId));
        link.setActive(false);
        joinLinkRepository.save(link);

        auditService.log(actorId, orgId, "LINK_REVOKED", "JoinLink", link.getId(), Map.of());
    }

    @Transactional(readOnly = true)
    public JoinLinkInfoResponse getJoinLinkInfo(UUID token) {
        JoinLink link = joinLinkRepository.findByTokenAndActiveTrue(token)
                .orElseThrow(() -> new ResourceNotFoundException("JoinLink", token.toString()));
        return new JoinLinkInfoResponse(
                link.getOrganization().getName(),
                link.isUsable(),
                link.isExpired(),
                link.getExpiresAt());
    }

    @Transactional
    public AuthResponse acceptJoinLink(UUID token, AcceptJoinLinkRequest request) {
        JoinLink link = joinLinkRepository.findByTokenAndActiveTrue(token)
                .orElseThrow(() -> new ResourceNotFoundException("JoinLink", token.toString()));

        if (!link.isUsable()) {
            throw new BadRequestException("This join link is expired or no longer active");
        }

        Organization org = link.getOrganization();
        if (!org.isActive()) {
            throw new BadRequestException("This organization is no longer active");
        }

        String normalizedEmail = request.email().toLowerCase().trim();

        // Account-takeover gate: this endpoint is unauthenticated, so we only
        // ever provision a brand-new user from a join link. Reusing an existing
        // user record (different org or otherwise) here would let anyone with a
        // valid join token + a known email overwrite that user's password,
        // organization, and role. Existing users must instead log in and be
        // added to the new org through an authenticated flow.
        if (userRepository.findByEmail(normalizedEmail).isPresent()) {
            throw new BadRequestException(
                    "An account with this email already exists. Please sign in instead.");
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setName(request.name() != null && !request.name().isBlank()
                ? request.name()
                : normalizedEmail.split("@")[0]);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setOrganization(org);
        user.setRole(UserRole.MEMBER);
        user.setStatus(UserStatus.ACTIVE);
        user.setActivatedAt(Instant.now());
        User savedUser = userRepository.save(user);

        auditService.log(savedUser.getId(), org.getId(), OrgAuditActions.JOIN_LINK_USED,
                OrgAuditActions.ENTITY_JOIN_LINK, link.getId(),
                Map.of("email", normalizedEmail));

        eventPublisher.publishEvent(new MemberJoinedEvent(
                org.getId(), savedUser.getId(), savedUser.getUserType()));
        publishWorkshopJoin(link, savedUser.getId());

        // Mint a session and persist the refresh-token row, enabling rotation
        // and revocation on subsequent /refresh calls.
        return authService.issueSession(savedUser, null);
    }

    /**
     * Workshop-bound links also enrol the joiner into a team of that workshop.
     * The in-transaction event keeps the team assignment atomic with the join
     * while the workshops slice stays import-free of this one.
     */
    private void publishWorkshopJoin(JoinLink link, UUID userId) {
        if (link.getWorkshopId() != null) {
            eventPublisher.publishEvent(
                    new WorkshopEvents.JoinedViaLink(link.getWorkshopId(), userId));
        }
    }

    /**
     * Accept a self-serve join link as part of an SSO (e.g. "Continue with Google") sign-in.
     *
     * <p>Mirrors {@link #acceptJoinLink} but keys off the provider-verified email instead of a
     * password registration. Unlike the credential path — which can only provision a brand-new
     * user — SSO safely resolves an existing account too: {@link AuthService#resolveSsoUser}
     * already enforces the account-takeover and provider-mismatch guards, so an SSO sign-in can
     * never seize a password account. Membership is bound onto the resolved user <em>before</em>
     * the session is issued so the access token carries the new {@code orgId}/{@code role} — the
     * step the plain SSO login path omitted, leaving Google users out of the organization.
     */
    @Transactional
    public AuthResponse acceptJoinLinkViaSso(UUID token, String email, String avatarUrl,
                                             String provider, AuthService.ClientContext context) {
        JoinLink link = joinLinkRepository.findByTokenAndActiveTrue(token)
                .orElseThrow(() -> new ResourceNotFoundException("JoinLink", token.toString()));
        if (!link.isUsable()) {
            throw new BadRequestException("This join link is expired or no longer active");
        }
        Organization org = link.getOrganization();
        if (!org.isActive()) {
            throw new BadRequestException("This organization is no longer active");
        }

        User user = authService.resolveSsoUser(email, avatarUrl, provider);

        // SUPER_ADMIN is a platform-level (org-less) account. Binding it here would force it into a
        // tenant org and overwrite its role with MEMBER (see below), silently demoting the platform
        // admin — the join-link analogue of the guard InvitationService.requireInvitationBindable
        // already enforces on the invitation path. Reject outright.
        if (user.getRole() == UserRole.SUPER_ADMIN) {
            throw new BadRequestException(
                    "This is a platform account and cannot join an organization.");
        }

        // An account already bound to a different org keeps its membership — silently re-homing it
        // would move its history to a new tenant. Steer it to switch accounts instead.
        if (user.getOrganization() != null && !user.getOrganization().getId().equals(org.getId())) {
            throw new BadRequestException(
                    "This account is already a member of another organization.");
        }

        boolean isNewMembership = user.getOrganization() == null;
        if (isNewMembership) {
            user.setOrganization(org);
            user.setRole(UserRole.MEMBER);
            user.setStatus(UserStatus.ACTIVE);
            if (user.getActivatedAt() == null) {
                user.setActivatedAt(Instant.now());
            }
        }
        User savedUser = userRepository.save(user);

        if (isNewMembership) {
            auditService.log(savedUser.getId(), org.getId(), OrgAuditActions.JOIN_LINK_USED,
                    OrgAuditActions.ENTITY_JOIN_LINK, link.getId(),
                    Map.of("email", savedUser.getEmail()));
            eventPublisher.publishEvent(new MemberJoinedEvent(
                    org.getId(), savedUser.getId(), savedUser.getUserType()));
            publishWorkshopJoin(link, savedUser.getId());
        }

        return authService.issueSession(savedUser, context);
    }
}
