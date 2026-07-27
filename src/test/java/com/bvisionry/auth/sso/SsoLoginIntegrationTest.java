package com.bvisionry.auth.sso;

import com.bvisionry.auth.dto.AuthResponse;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.exception.SsoFlowException;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SsoLoginService} against a REAL schema.
 *
 * <p>{@code SsoLoginServiceTest} mocks the repositories, which is right for the
 * branch logic and wrong for the three things this class exists to check — all of
 * them invisible to a mock and to the compiler:
 *
 * <ol>
 *   <li><b>Three native queries.</b> {@code findOrganizationIdByUserId},
 *       {@code assignOrganization} and {@code findOrganizationActive} are raw SQL,
 *       which Hibernate does not validate at bootstrap. A wrong column name is
 *       green in every mocked test and throws on the first real enterprise login.
 *       They are native precisely because the ArchUnit ratchet forbids the Java
 *       equivalents, so nothing else covers them.</li>
 *   <li><b>The {@code clearAutomatically} + re-read contract.</b> JIT binds the org
 *       with an UPDATE that bypasses the persistence context. If the re-read were
 *       dropped — or the clear were — {@code issueSession} would receive a User
 *       whose {@code organization} is still null, and the access token would carry
 *       no {@code orgId}: the member signs in successfully and then sees an empty
 *       app. Asserting the org on the returned session is what pins it.</li>
 *   <li><b>The FK and the ON DELETE CASCADE</b> actually holding.</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfDockerAvailable
class SsoLoginIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String REGISTRATION = "orgb-okta";

    @Autowired private SsoLoginService ssoLoginService;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;
    private UUID otherTenantId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM sso_registrations");
        jdbc.update("DELETE FROM users WHERE email LIKE '%@orgb.com'");
        tenantId = organization("OrgB");
        otherTenantId = organization("OrgC");
        registration(REGISTRATION, tenantId, "orgb.com", true);
    }

    private UUID organization(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, is_active) VALUES (?, ?, TRUE)", id, name);
        return id;
    }

    private void registration(String registrationId, UUID orgId, String domain, boolean enabled) {
        jdbc.update("""
                INSERT INTO sso_registrations
                    (id, registration_id, org_id, protocol, email_domain, display_name, enabled, saml_metadata)
                VALUES (?, ?, ?, 'SAML', ?, 'OrgB IdP', ?, '<EntityDescriptor/>')
                """, UUID.randomUUID(), registrationId, orgId, domain, enabled);
    }

    private AuthResponse login(String email) {
        return ssoLoginService.completeLogin(REGISTRATION, email, null, null);
    }

    @Test
    void aFirstTimeUserIsProvisionedAndTheSessionCarriesTheOrgId() {
        AuthResponse auth = login("newjoiner@orgb.com");

        // THE contract: assignOrganization's UPDATE landed AND the re-read put the
        // org back on the entity that built this session. A dropped re-read leaves
        // this null while every mocked test stays green.
        assertThat(auth.user().organizationId())
                .as("the access token's orgId comes from this field")
                .isEqualTo(tenantId);
        assertThat(auth.user().role()).isEqualTo(UserRole.MEMBER);
        assertThat(auth.token()).isNotBlank();

        assertThat(jdbc.queryForObject(
                "SELECT organization_id FROM users WHERE email = 'newjoiner@orgb.com'", UUID.class))
                .isEqualTo(tenantId);
        assertThat(jdbc.queryForObject(
                "SELECT password_hash IS NULL AND sso_provider IS NULL FROM users WHERE email = 'newjoiner@orgb.com'",
                Boolean.class))
                .as("no password, and no provider pin that would block a later Google sign-in")
                .isTrue();
    }

    @Test
    void aSecondLoginReusesTheSameAccountAndDoesNotReprovision() {
        UUID first = login("repeat@orgb.com").user().id();
        UUID second = login("repeat@orgb.com").user().id();

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = 'repeat@orgb.com'", Integer.class)).isEqualTo(1);
    }

    @Test
    void anInDomainUserBelongingToAnotherOrgIsRefusedAgainstTheRealColumn() {
        // Exercises findOrganizationIdByUserId for real: a wrong column name here is
        // the difference between a refusal and a cross-tenant sign-in.
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, name, role, status, organization_id, created_at, updated_at)
                VALUES (?, 'elsewhere@orgb.com', 'Elsewhere', 'MEMBER', 'ACTIVE', ?, NOW(), NOW())
                """, userId, otherTenantId);

        assertThatThrownBy(() -> login("elsewhere@orgb.com"))
                .isInstanceOf(SsoFlowException.class)
                .extracting(e -> ((SsoFlowException) e).getErrorCode())
                .isEqualTo("sso_other_org");
    }

    @Test
    void aSuspendedTenantIsRefusedAgainstTheRealColumn() {
        // Exercises findOrganizationActive for real.
        jdbc.update("UPDATE organizations SET is_active = FALSE WHERE id = ?", tenantId);

        assertThatThrownBy(() -> login("newjoiner@orgb.com"))
                .extracting(e -> ((SsoFlowException) e).getErrorCode())
                .isEqualTo("sso_org_suspended");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = 'newjoiner@orgb.com'", Integer.class))
                .as("a refusal must not leave a half-provisioned account behind")
                .isEqualTo(0);
    }

    @Test
    void aPlatformAdminIsRefusedAgainstARealRow() {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, name, role, status, created_at, updated_at)
                VALUES (?, 'platform@orgb.com', 'Platform', 'SUPER_ADMIN', 'ACTIVE', NOW(), NOW())
                """, userId);

        assertThatThrownBy(() -> login("platform@orgb.com"))
                .extracting(e -> ((SsoFlowException) e).getErrorCode())
                .isEqualTo("sso_platform_account");
    }

    @Test
    void deletingTheOrganizationTakesItsRegistrationWithIt() {
        // The FK's ON DELETE CASCADE: a registration that outlived its org would keep
        // answering /start with a handshake into a tenant that no longer exists.
        jdbc.update("DELETE FROM organizations WHERE id = ?", tenantId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sso_registrations WHERE registration_id = ?",
                Integer.class, REGISTRATION)).isEqualTo(0);
    }
}
