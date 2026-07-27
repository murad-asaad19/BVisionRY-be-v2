package com.bvisionry.auth.sso;

import com.bvisionry.auth.AuthService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.dto.AuthResponse;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.exception.SsoFlowException;
import com.bvisionry.organization.entity.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The three invariants, the provider-mismatch rule and JIT provisioning.
 *
 * <p>Every refusal test asserts the error CODE, not merely that something threw,
 * so a different refusal firing first would also be caught.
 *
 * <p><b>Two fixture shapes, and the distinction is load-bearing.</b> A
 * {@code returningUser} carries a {@code lastLoginAt} from a month ago; a
 * {@code firstLoginUser} has none. Invariants 2 and 3 are claimed to hold on
 * EVERY login, not only the first — and with a single first-login-shaped fixture
 * that claim would live in the test names and nowhere else: making either refusal
 * conditional on {@code getLastLoginAt() == null} (exactly the "guard only the
 * provisioning path" mistake the design forbids) would leave the suite green.
 * Both invariants are therefore exercised against both shapes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SsoLoginServiceTest {

    private static final String REGISTRATION = "orgb-okta";
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();

    @Mock private SsoRegistrationRepository registrationRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuthService authService;

    private SsoLoginService service;
    private SsoRegistration registration;

    @BeforeEach
    void setUp() {
        service = new SsoLoginService(registrationRepository, userRepository, authService);

        registration = new SsoRegistration();
        registration.setRegistrationId(REGISTRATION);
        registration.setOrgId(TENANT);
        registration.setEmailDomain("orgb.com");
        registration.setProtocol(SsoRegistration.Protocol.SAML);
        registration.setEnabled(true);

        when(registrationRepository.findByRegistrationIdAndEnabledTrue(REGISTRATION))
                .thenReturn(Optional.of(registration));
        when(registrationRepository.findOrganizationActive(TENANT)).thenReturn(true);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        when(authService.issueSession(any(User.class), any()))
                .thenReturn(new AuthResponse(null, "access", "refresh"));
    }

    private AuthResponse login(String email) {
        return service.completeLogin(REGISTRATION, email, null, null);
    }

    /** An account that has signed in before — the shape almost every real login has. */
    private static User returningUser(UserRole role, UserStatus status) {
        User user = user(role, status);
        user.setLastLoginAt(Instant.now().minus(30, ChronoUnit.DAYS));
        return user;
    }

    /** An account that exists but has never signed in (e.g. created by an invitation). */
    private static User firstLoginUser(UserRole role, UserStatus status) {
        return user(role, status);
    }

    private static User user(UserRole role, UserStatus status) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("founder@orgb.com");
        user.setName("Founder");
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    private static User shaped(String shape, UserRole role) {
        return "returning".equals(shape)
                ? returningUser(role, UserStatus.ACTIVE)
                : firstLoginUser(role, UserStatus.ACTIVE);
    }

    private void existing(User user, UUID orgId) {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(userRepository.findOrganizationIdByUserId(user.getId())).thenReturn(orgId);
    }

    private static Organization organization(UUID id) {
        Organization organization = new Organization();
        organization.setId(id);
        return organization;
    }

    /**
     * An assertion authenticates; it never administers. Captures the User actually
     * handed to the session minter, because that — not the row we started from — is
     * what the access token's role and orgId claims are built from.
     */
    private void assertSessionCarriedUnchanged(UserRole expectedRole, Organization expectedOrg) {
        ArgumentCaptor<User> issued = ArgumentCaptor.forClass(User.class);
        verify(authService).issueSession(issued.capture(), any());
        assertThat(issued.getValue().getRole())
                .as("an SSO assertion must never change an existing user's role")
                .isEqualTo(expectedRole);
        assertThat(issued.getValue().getOrganization())
                .as("an SSO assertion must never change an existing user's organization")
                .isSameAs(expectedOrg);
    }

    // ---------------------------------------------------------------- invariant 1

    @Test
    void invariant1_anOutOfDomainAssertionIsRefused() {
        assertThatThrownBy(() -> login("attacker@evil-orgb.com"))
                .isInstanceOf(SsoFlowException.class)
                .extracting(e -> ((SsoFlowException) e).getErrorCode())
                .isEqualTo("sso_domain_mismatch");
        verifyNoInteractions(authService);
    }

    @Test
    void invariant1_refusesBeforeAnyUserIsEvenLookedUp() {
        // The domain gate is the first thing an untrusted assertion meets. If it
        // moved below the user lookup, an out-of-domain assertion would at minimum
        // become an account-existence oracle.
        assertThatThrownBy(() -> login("victim@othercompany.com")).isInstanceOf(SsoFlowException.class);
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void invariant1_aHomoglyphDomainIsRefused() {
        assertThatThrownBy(() -> login("attacker@оrgb.com"))
                .extracting(e -> ((SsoFlowException) e).getErrorCode())
                .isEqualTo("sso_domain_mismatch");
    }

    @Test
    void invariant1_anUnquotedLocalPartCarryingASecondAddressIsRefused() {
        // "ceo@orgc.com@orgb.com" reads as domain orgb.com and would pass a gate that
        // only looked at the domain, then be STORED and rendered in org B's member
        // list as another company's executive. Not takeover — display spoofing, which
        // is exactly why it survives a glance at the domain check.
        assertThatThrownBy(() -> login("ceo@orgc.com@orgb.com"))
                .extracting(e -> ((SsoFlowException) e).getErrorCode())
                .isEqualTo("sso_domain_mismatch");
        verifyNoInteractions(authService);
    }

    // ---------------------------------------------------------------- invariant 2

    @ParameterizedTest(name = "[{index}] {0} login")
    @ValueSource(strings = {"returning", "first"})
    void invariant2_aPlatformAdminIsRefusedEvenInsideTheVerifiedDomain(String shape) {
        // In-domain on purpose: this proves the refusal is unconditional rather than
        // a side effect of the domain gate. Both shapes, so making it conditional on
        // "has never logged in" cannot pass.
        existing(shaped(shape, UserRole.SUPER_ADMIN), null);

        assertThatThrownBy(() -> login("founder@orgb.com"))
                .extracting(e -> ((SsoFlowException) e).getErrorCode())
                .isEqualTo("sso_platform_account");
        verifyNoInteractions(authService);
    }

    @Test
    void invariant2_provisioningCanOnlyEverCreateAMember() {
        // The other half of "unconditional": no path here mints a role, so a
        // brand-new account cannot arrive as an admin of any kind.
        provisioning();
        login("newjoiner@orgb.com");

        ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(created.capture());
        assertThat(created.getValue().getRole()).isEqualTo(UserRole.MEMBER);
    }

    // ---------------------------------------------------------------- invariant 3

    @ParameterizedTest(name = "[{index}] {0} login")
    @ValueSource(strings = {"returning", "first"})
    void invariant3_anInDomainUserWhoBelongsToAnotherOrgIsRefusedOnEveryLogin(String shape) {
        // Domain uniqueness cannot catch this: the address IS at orgb.com, but this
        // account was invited into a different tenant earlier and must stay there.
        // "returning" is the case that matters — a refusal that only fires on the
        // provisioning path lets every subsequent login straight through.
        existing(shaped(shape, UserRole.MEMBER), OTHER_TENANT);

        assertThatThrownBy(() -> login("founder@orgb.com"))
                .extracting(e -> ((SsoFlowException) e).getErrorCode())
                .isEqualTo("sso_other_org");
        verifyNoInteractions(authService);
    }

    @Test
    void invariant3_aReturningUserInTheRegistrationsOrgSignsInWithRoleAndOrgUntouched() {
        User member = returningUser(UserRole.MEMBER, UserStatus.ACTIVE);
        Organization org = organization(TENANT);
        member.setOrganization(org);
        existing(member, TENANT);

        assertThat(login("founder@orgb.com").token()).isEqualTo("access");
        assertSessionCarriedUnchanged(UserRole.MEMBER, org);
    }

    @Test
    void anAssertionCannotPromoteAnOrgAdminOrAnyoneElse() {
        // The ROLE half of "never changes an existing user's role or organization".
        // Invariant 2 only refuses someone who ALREADY is a platform admin; nothing
        // else stops this path from MAKING one and minting it straight into a token,
        // so the role that reaches issueSession is asserted rather than assumed.
        User orgAdmin = returningUser(UserRole.ORG_ADMIN, UserStatus.ACTIVE);
        Organization org = organization(TENANT);
        orgAdmin.setOrganization(org);
        existing(orgAdmin, TENANT);

        login("founder@orgb.com");
        assertSessionCarriedUnchanged(UserRole.ORG_ADMIN, org);
    }

    @Test
    void invariant3_anOrgLessExistingUserSignsInAndIsNotAbsorbedIntoTheTenant() {
        // An assertion authenticates; it never administers. A user who predates the
        // connection keeps their (absent) membership rather than being silently
        // moved into the buying org.
        User member = returningUser(UserRole.MEMBER, UserStatus.ACTIVE);
        existing(member, null);

        login("founder@orgb.com");

        verify(userRepository, never()).assignOrganization(any(), any());
        assertSessionCarriedUnchanged(UserRole.MEMBER, null);
    }

    // ------------------------------------------------------- provider-mismatch rule

    @Test
    void aGoogleLinkedAccountAuthenticatesAndKeepsItsStoredProvider() {
        // The domain-verified enterprise path must not trip the Google flow's
        // provider pin — that would lock out every existing Google user at the
        // buying org on day one — and must not overwrite it either.
        User member = returningUser(UserRole.MEMBER, UserStatus.ACTIVE);
        member.setSsoProvider("GOOGLE");
        existing(member, TENANT);

        assertThat(login("founder@orgb.com").token()).isEqualTo("access");
        assertThat(member.getSsoProvider()).isEqualTo("GOOGLE");
    }

    @Test
    void provisioningLeavesTheProviderPinUnsetSoGoogleStillWorksLater() {
        provisioning();
        login("newjoiner@orgb.com");

        ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(created.capture());
        assertThat(created.getValue().getSsoProvider())
                .as("pinning a provider here would make a later Google sign-in a mismatch")
                .isNull();
    }

    // ------------------------------------------------------------ JIT provisioning

    @Test
    void aFirstTimeInDomainUserIsProvisionedIntoTheRegistrationsOrg() {
        provisioning();

        assertThat(login("newjoiner@orgb.com").token()).isEqualTo("access");

        ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(created.capture());
        assertThat(created.getValue().getEmail()).isEqualTo("newjoiner@orgb.com");
        assertThat(created.getValue().getName()).isEqualTo("newjoiner");
        assertThat(created.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(created.getValue().getPasswordHash()).isNull();
        verify(userRepository).assignOrganization(created.getValue().getId(), TENANT);
    }

    // --------------------------------------------------------------------- avatar

    @Test
    void anExistingUsersAvatarIsNeverOverwritten() {
        // The IdP's picture claim is profile data the user may have deliberately
        // replaced here; a login is not the moment to reset it.
        User member = returningUser(UserRole.MEMBER, UserStatus.ACTIVE);
        member.setAvatarUrl("https://ours/chosen.png");
        existing(member, TENANT);

        service.completeLogin(REGISTRATION, "founder@orgb.com", "https://idp/theirs.png", null);

        assertThat(member.getAvatarUrl()).isEqualTo("https://ours/chosen.png");
    }

    @Test
    void anExistingUserWithNoAvatarPicksUpTheOneTheIdpSent() {
        User member = returningUser(UserRole.MEMBER, UserStatus.ACTIVE);
        existing(member, TENANT);

        service.completeLogin(REGISTRATION, "founder@orgb.com", "https://idp/theirs.png", null);

        assertThat(member.getAvatarUrl()).isEqualTo("https://idp/theirs.png");
    }

    // ------------------------------------------------------------- other refusals

    @Test
    void anUnknownOrDisabledRegistrationIsRefused() {
        when(registrationRepository.findByRegistrationIdAndEnabledTrue("gone"))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.completeLogin("gone", "founder@orgb.com", null, null))
                .extracting(e -> ((SsoFlowException) e).getErrorCode())
                .isEqualTo("sso_registration_unknown");
    }

    @Test
    void anAssertionCarryingNoEmailIsRefused() {
        assertThatThrownBy(() -> login(null))
                .extracting(e -> ((SsoFlowException) e).getErrorCode())
                .isEqualTo("sso_no_email");
    }

    @Test
    void aSuspendedTenantCannotBeReachedThroughItsIdp() {
        when(registrationRepository.findOrganizationActive(TENANT)).thenReturn(false);
        assertThatThrownBy(() -> login("founder@orgb.com"))
                .extracting(e -> ((SsoFlowException) e).getErrorCode())
                .isEqualTo("sso_org_suspended");
    }

    @Test
    void aRegistrationPointingAtAMissingOrgFailsClosed() {
        when(registrationRepository.findOrganizationActive(TENANT)).thenReturn(null);
        assertThatThrownBy(() -> login("founder@orgb.com"))
                .extracting(e -> ((SsoFlowException) e).getErrorCode())
                .isEqualTo("sso_org_suspended");
    }

    @Test
    void aNonActiveAccountIsRefused() {
        existing(returningUser(UserRole.MEMBER, UserStatus.SUSPENDED), TENANT);

        assertThatThrownBy(() -> login("founder@orgb.com"))
                .extracting(e -> ((SsoFlowException) e).getErrorCode())
                .isEqualTo("sso_account_inactive");
    }

    /** Wire the repository for a first-ever sign-in, including the post-bind re-read. */
    private void provisioning() {
        when(userRepository.findByEmail("newjoiner@orgb.com")).thenReturn(Optional.empty());
        when(userRepository.findByIdWithOrganization(any())).thenAnswer(invocation -> {
            User reread = new User();
            reread.setId(invocation.getArgument(0));
            reread.setEmail("newjoiner@orgb.com");
            reread.setName("newjoiner");
            reread.setRole(UserRole.MEMBER);
            reread.setStatus(UserStatus.ACTIVE);
            return Optional.of(reread);
        });
    }
}
