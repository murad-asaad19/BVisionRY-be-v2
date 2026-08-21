package com.bvisionry.media;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default-quota path against REAL SQL. {@link OrgStorageQuotaServiceTest}
 * mocks {@link OrgStorageQuotaRepository} and therefore cannot see what the
 * query actually returns — it stubs {@code quotaOverrideBytes} to null and
 * proves only the service's own branch.
 *
 * <p>THE BUG THIS EXISTS FOR: {@code organizations.storage_quota_bytes} is NULL
 * for every org without an explicit override, which is the DEFAULT and by far
 * the commonest state. The row-mapper therefore produced a null element, and
 * {@code Stream.findFirst()} throws NullPointerException on a null head — so
 * every upload by a default-quota org 500'd (MediaService.upload →
 * requireCapacity → effectiveQuotaBytes → NPE) while the mocked unit test
 * stayed green. Only a test that executes the query can catch this.
 *
 * <p>{@code @Transactional}: the Testcontainers Postgres is a singleton shared
 * by every IT class in the JVM, so the orgs seeded here are rolled back.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfDockerAvailable
@Transactional
class OrgStorageQuotaDefaultOverrideIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrgStorageQuotaRepository repository;
    @Autowired private OrgStorageQuotaService service;
    @Autowired private MediaProperties props;

    @Test
    void anOrgWithNoOverrideReadsAsNoOverrideRatherThanThrowing() {
        UUID orgId = org(null);

        assertThat(repository.quotaOverrideBytes(orgId)).isNull();
    }

    @Test
    void anOrgWithNoOverrideGetsThePlatformDefaultQuota() {
        UUID orgId = org(null);

        assertThat(service.effectiveQuotaBytes(orgId))
                .isEqualTo(props.getOrgDefaultQuotaBytes());
    }

    @Test
    void anExplicitOverrideStillWinsOverThePlatformDefault() {
        // The other half of the branch: filtering nulls out must not also
        // swallow a real override.
        long override = 5L * 1024 * 1024 * 1024;
        UUID orgId = org(override);

        assertThat(repository.quotaOverrideBytes(orgId)).isEqualTo(override);
        assertThat(service.effectiveQuotaBytes(orgId)).isEqualTo(override);
    }

    @Test
    void anUnknownOrgFallsBackToThePlatformDefault() {
        // Documented behaviour: tenancy is enforced upstream, so "no row" is
        // not a distinct case — it reads the same as "no override".
        assertThat(service.effectiveQuotaBytes(UUID.randomUUID()))
                .isEqualTo(props.getOrgDefaultQuotaBytes());
    }

    /** @param quotaBytes the override, or null for the default (the buggy case) */
    private UUID org(Long quotaBytes) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO organizations (id, name, is_active, storage_quota_bytes)
                VALUES (?, ?, true, ?)
                """, id, "Quota Org " + id, quotaBytes);
        return id;
    }
}
