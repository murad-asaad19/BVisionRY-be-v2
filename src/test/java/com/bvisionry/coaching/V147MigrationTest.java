package com.bvisionry.coaching;

import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The MANAGER→MEMBER data migration, tested for real: replay the schema up to
 * V146 in a scratch schema, insert MANAGER holders (a user plus PENDING and
 * ACCEPTED invitations — all legal under the V75 CHECK), migrate to head, and
 * assert V147 converted every one of them. Terminal invitations matter as much
 * as pending ones: {@code Invitation.role} hydrates through the {@code UserRole}
 * enum, so a single surviving MANAGER row would throw on read and 500 the
 * whole invitations surface. The main app schema — where Flyway has already
 * passed V147 — additionally proves the widened CHECK is a pure expansion and
 * that converted rows hydrate cleanly through the JPA surface.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfDockerAvailable
class V147MigrationTest extends AbstractPostgresIntegrationTest {

    private static final String SCRATCH = "v147_replay";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.bvisionry.organization.InvitationRepository invitationRepository;
    @Autowired private com.bvisionry.organization.InvitationService invitationService;

    @Test
    void managerHoldersAndAllManagerInvitationsBecomeMembers() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCRATCH + " CASCADE");

        migrate("146");
        UUID orgId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID managerId = UUID.randomUUID();
        jdbc.update("INSERT INTO " + SCRATCH + ".organizations (id, name) VALUES (?, 'Replay Org')", orgId);
        jdbc.update("""
                INSERT INTO %s.users (id, email, name, role, status, organization_id)
                VALUES (?, 'replay-admin@test.invalid', 'Admin', 'ORG_ADMIN', 'ACTIVE', ?)
                """.formatted(SCRATCH), adminId, orgId);
        jdbc.update("""
                INSERT INTO %s.users (id, email, name, role, status, organization_id)
                VALUES (?, 'replay-manager@test.invalid', 'Manager', 'MANAGER', 'ACTIVE', ?)
                """.formatted(SCRATCH), managerId, orgId);
        jdbc.update("""
                INSERT INTO %s.invitations (email, organization_id, role, status, invited_by, expires_at)
                VALUES ('replay-invite@test.invalid', ?, 'MANAGER', 'PENDING', ?, now() + interval '7 days')
                """.formatted(SCRATCH), orgId, adminId);
        // The latent 500: an already-ACCEPTED MANAGER invitation survives as a
        // terminal row that still hydrates on every invitations-list read.
        jdbc.update("""
                INSERT INTO %s.invitations (email, organization_id, role, status, invited_by, expires_at, accepted_at)
                VALUES ('replay-accepted@test.invalid', ?, 'MANAGER', 'ACCEPTED', ?, now() - interval '1 day', now())
                """.formatted(SCRATCH), orgId, adminId);

        migrate("latest");

        assertThat(jdbc.queryForObject(
                "SELECT role FROM " + SCRATCH + ".users WHERE id = ?", String.class, managerId))
                .isEqualTo("MEMBER");
        assertThat(jdbc.queryForObject(
                "SELECT role FROM " + SCRATCH + ".invitations WHERE email = 'replay-invite@test.invalid'",
                String.class))
                .isEqualTo("MEMBER");
        assertThat(jdbc.queryForObject(
                "SELECT role FROM " + SCRATCH + ".invitations WHERE email = 'replay-accepted@test.invalid'",
                String.class))
                .isEqualTo("MEMBER");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM " + SCRATCH + ".users WHERE role = 'MANAGER'", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM " + SCRATCH + ".invitations WHERE role = 'MANAGER'", Long.class))
                .isZero();

        jdbc.execute("DROP SCHEMA " + SCRATCH + " CASCADE");
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void convertedTerminalInvitationHydratesThroughTheJpaSurface() {
        // Main schema (post-V147): a terminal invitation in the CONVERTED shape
        // — role MEMBER, status ACCEPTED — must hydrate through the enum-typed
        // entity and survive the whole admin list read without throwing.
        UUID orgId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, is_active) VALUES (?, 'Hydration Org', true)", orgId);
        jdbc.update("""
                INSERT INTO users (id, email, name, role, status, organization_id)
                VALUES (?, 'hydration-admin@test.invalid', 'Admin', 'ORG_ADMIN', 'ACTIVE', ?)
                """, adminId, orgId);
        jdbc.update("""
                INSERT INTO invitations (email, organization_id, role, status, invited_by, expires_at, accepted_at)
                VALUES ('hydration-accepted@test.invalid', ?, 'MEMBER', 'ACCEPTED', ?, now() - interval '1 day', now())
                """, orgId, adminId);

        assertThat(invitationRepository.findByOrganizationId(orgId)).hasSize(1);
        assertThat(invitationService.listByOrganization(orgId)).hasSize(1);
    }

    @Test
    void widenedCheckIsAPureExpansion_coachInAndManagerStillAllowed() {
        // Runs against the MAIN schema, already at head.
        assertThatCode(() -> {
            jdbc.update("""
                    INSERT INTO users (email, name, role, status)
                    VALUES ('check-coach@test.invalid', 'Coach', 'COACH', 'ACTIVE')
                    """);
            // The contraction dropping MANAGER from the CHECK is deferred: the
            // value stays legal at the DB even though the enum no longer has it.
            jdbc.update("""
                    INSERT INTO users (email, name, role, status)
                    VALUES ('check-manager@test.invalid', 'Legacy', 'MANAGER', 'ACTIVE')
                    """);
        }).doesNotThrowAnyException();
        jdbc.update("DELETE FROM users WHERE email IN "
                + "('check-coach@test.invalid', 'check-manager@test.invalid')");
    }

    private void migrate(String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas(SCRATCH)
                .defaultSchema(SCRATCH)
                .target(target)
                .load()
                .migrate();
    }
}
