package com.bvisionry.auth.sso;

import com.bvisionry.auth.AuthService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.dto.AuthResponse;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.exception.SsoFlowException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Turns a validated enterprise assertion into a platform session.
 *
 * <p>Everything cryptographic already happened before this class is reached:
 * {@code OpenSaml5AuthenticationProvider} (SAML) and
 * {@code OidcAuthorizationCodeAuthenticationProvider} (OIDC) verified the
 * signature, audience, {@code InResponseTo}/{@code state} and recipient using
 * library defaults. Nothing here re-implements or relaxes any of that. What is
 * left is the AUTHORIZATION question the library cannot answer: which of OUR
 * users, if any, is this IdP allowed to speak for.
 *
 * <h2>The three invariants</h2>
 * <ol>
 *   <li><strong>Domain</strong> — the asserted email's domain must equal the
 *       registration's verified domain by exact label match (see
 *       {@link EmailDomains}). A customer's IdP can assert any string it likes;
 *       this is what stops it asserting a string at someone else's company.</li>
 *   <li><strong>No platform admin, ever</strong> — a SUPER_ADMIN session is never
 *       mintable from a customer-controlled IdP, in-domain or not. Anyone who can
 *       operate the IdP could otherwise become a platform administrator.</li>
 *   <li><strong>No other tenant, on every login</strong> — checked for existing
 *       users at every sign-in, not only at provisioning. Domain uniqueness stops
 *       two registrations claiming one domain; it does NOT stop an in-domain user
 *       who was invited into a different org earlier.</li>
 * </ol>
 *
 * <h2>What this path deliberately does NOT do</h2>
 * It never reads or writes {@code User.ssoProvider}. That field is the Google
 * flow's anti-confusion pin ("this email already signs in with X"), and enforcing
 * it here would lock out every existing Google user at the buying organization on
 * day one. It is safe to skip precisely because domain verification is the
 * stronger claim: an org that provably controls the mailbox could already take the
 * account by password reset, so the assertion grants no new power. Leaving the
 * field untouched also means a JIT-provisioned user can still use Google later.
 *
 * <p>It also never changes an existing user's role or organization. An assertion
 * authenticates; it does not administer.
 */
@Service
@RequiredArgsConstructor
public class SsoLoginService {

    private static final Logger log = LoggerFactory.getLogger(SsoLoginService.class);

    private final SsoRegistrationRepository registrationRepository;
    private final UserRepository userRepository;
    private final AuthService authService;

    /**
     * @param registrationId which registration completed the handshake
     * @param assertedEmail  the email the IdP asserted — untrusted until invariant 1 passes
     * @param avatarUrl      optional profile picture (OIDC only); never overwrites an existing one
     * @throws SsoFlowException carrying the stable {@code ?error=} code the login page renders
     */
    @Transactional
    public AuthResponse completeLogin(String registrationId, String assertedEmail, String avatarUrl,
                                      AuthService.ClientContext context) {
        SsoRegistration registration = registrationRepository
                .findByRegistrationIdAndEnabledTrue(registrationId)
                .orElseThrow(() -> new SsoFlowException("sso_registration_unknown",
                        "This single sign-on connection is no longer active."));

        String email = EmailDomains.normalizeEmail(assertedEmail);
        if (email == null || email.isBlank()) {
            throw new SsoFlowException("sso_no_email",
                    "Your identity provider did not send an email address.");
        }

        // INVARIANT 1 — exact-label domain match. Never endsWith; never a suffix.
        if (!EmailDomains.matches(registration.getEmailDomain(), email)) {
            log.warn("SSO registration {} asserted an out-of-domain address (expected domain {})",
                    registrationId, registration.getEmailDomain());
            throw new SsoFlowException("sso_domain_mismatch",
                    "This sign-in is only available to members of the connected organization's email domain.");
        }

        requireActiveOrganization(registration);

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return authService.issueSession(provision(email, avatarUrl, registration), context);
        }

        // INVARIANT 2 — unconditional platform-admin refusal. Reached only for an
        // IN-DOMAIN address (invariant 1 already passed), which is the point: even a
        // super admin whose mailbox lives at the customer's verified domain cannot be
        // signed in by the customer's IdP. There is no carve-out.
        if (user.getRole() == UserRole.SUPER_ADMIN) {
            log.warn("SSO registration {} attempted to authenticate a platform administrator", registrationId);
            throw new SsoFlowException("sso_platform_account",
                    "This is a platform administrator account and cannot sign in through an organization's "
                            + "identity provider.");
        }

        // INVARIANT 3 — other-org refusal, on EVERY login. `null` (org-less) is
        // allowed through and stays null: an assertion never changes an existing
        // user's organization, so a user who predates the connection signs in as
        // themselves rather than being silently absorbed into the tenant.
        UUID currentOrgId = userRepository.findOrganizationIdByUserId(user.getId());
        if (currentOrgId != null && !currentOrgId.equals(registration.getOrgId())) {
            log.warn("SSO registration {} attempted to authenticate a member of another organization",
                    registrationId);
            // STRICT EQUALITY, and that is a decision rather than an oversight. This
            // org model is one level deep: members live in SUB-organizations, so a
            // registration created against a PARENT org refuses every one of its own
            // children's members. Whether a parent's identity provider may speak for
            // its sub-orgs is a trust question about what customers were promised, not
            // a coding one, and it is ESCALATED TO THE OPERATOR — do not relax this to
            // a hierarchy walk without that answer. Shipping fail-closed is the
            // recoverable direction: an operator can register the sub-org, but cannot
            // un-issue a session that should never have existed.
            //
            // The message therefore avoids "another organization", which is actively
            // misleading in the sub-org case (it is the SAME customer) and would send
            // an admin hunting for a membership conflict that does not exist.
            throw new SsoFlowException("sso_other_org",
                    "This account is not a member of the organization connected to this identity provider.");
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new SsoFlowException("sso_account_inactive", "Account is not active");
        }

        if (avatarUrl != null && user.getAvatarUrl() == null) {
            user.setAvatarUrl(avatarUrl);
        }
        user.setLastLoginAt(Instant.now());
        return authService.issueSession(user, context);
    }

    /**
     * Just-in-time provisioning: a first-time in-domain user becomes an ACTIVE
     * MEMBER of the registration's organization, with no password and no
     * {@code ssoProvider} pin.
     *
     * <p>MEMBER is hard-coded, which is also what makes invariant 2 total: no code
     * path here can mint a role, so a brand-new account can never arrive as an
     * admin of any kind.
     *
     * <p>No {@code MemberJoinedEvent} is published. That is the invitation/join-link
     * signal and it fans out to auto-assignment, which materialises pipeline work
     * and paid AI evaluation for the new member; an authentication event must not
     * spend money. (It is also an {@code organization} type, which {@code auth}
     * may not import.)
     */
    private User provision(String email, String avatarUrl, SsoRegistration registration) {
        User user = new User();
        user.setEmail(email);
        // Same display-name convention as password registration and invitation
        // acceptance: the local part, since no IdP profile name is carried through.
        user.setName(email.substring(0, email.indexOf('@')));
        user.setRole(UserRole.MEMBER);
        user.setStatus(UserStatus.ACTIVE);
        user.setActivatedAt(Instant.now());
        user.setLastLoginAt(Instant.now());
        user.setAvatarUrl(avatarUrl);
        User saved = userRepository.save(user);

        // Two statements, not `saved.setOrganization(...)`: setting the association
        // needs the Organization type, and auth may not import it. The update clears
        // the persistence context (see UserRepository#assignOrganization), so the
        // re-read is what makes `organization` — and therefore the token's orgId —
        // visible on the entity handed to issueSession.
        userRepository.assignOrganization(saved.getId(), registration.getOrgId());
        return userRepository.findByIdWithOrganization(saved.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Provisioned SSO user disappeared: " + saved.getId()));
    }

    /**
     * A suspended tenant must not be reachable through its IdP either.
     *
     * <p>Checking the REGISTRATION's org is sufficient for every outcome: a
     * provisioned user lands in exactly this org, and an existing user only gets
     * past invariant 3 if their org is this one or null. A user whose org is
     * something else is refused by invariant 3 regardless of what this returns.
     */
    private void requireActiveOrganization(SsoRegistration registration) {
        Boolean active = registrationRepository.findOrganizationActive(registration.getOrgId());
        if (!Boolean.TRUE.equals(active)) {
            throw new SsoFlowException("sso_org_suspended",
                    "This organization is no longer active. Contact support for assistance.");
        }
    }
}
