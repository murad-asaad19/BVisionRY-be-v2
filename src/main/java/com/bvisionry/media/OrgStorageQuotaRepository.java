package com.bvisionry.media;

import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads an org's storage-quota override straight off the {@code organizations}
 * table via raw SQL — no JPA entity, no {@code organization} package import.
 *
 * <p>Same seam as {@code insights.BenchmarkReadRepository} and
 * {@code common.gdpr.PersonalDataRepository}: the ArchUnit ratchet
 * ({@code noCrossFeatureDependencies}) forbids a NEW feature→feature edge —
 * the frozen store already documents {@code organization} never importing
 * {@code media}, and the converse edge doesn't exist either — so this class
 * depends on the schema instead of the owning feature's Java types.
 */
@Repository
public class OrgStorageQuotaRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public OrgStorageQuotaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The org's {@code storage_quota_bytes} override, or {@code null} when
     * unset — callers apply the platform default in that case. An org id that
     * does not exist reads the same as "no override"; the caller already holds
     * a validated org id by the time quota is checked (tenancy is enforced
     * upstream), so this method has no separate not-found case.
     */
    public Long quotaOverrideBytes(UUID orgId) {
        return jdbc.query(
                "SELECT storage_quota_bytes FROM organizations WHERE id = :orgId",
                new MapSqlParameterSource("orgId", orgId),
                (rs, i) -> rs.getObject("storage_quota_bytes", Long.class))
                .stream().findFirst().orElse(null);
    }
}
