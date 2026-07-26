package com.bvisionry.pipeline.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The pipeline slice's only look at the catalog: what a mapped course is CALLED
 * and what state it is in.
 *
 * <p><strong>READ THIS BEFORE ADDING A SECOND CALLER.</strong> The one query
 * here is <em>unscoped in every dimension</em> — any org, any state, no
 * soft-delete or visibility predicate — and being raw SQL it is invisible to
 * the {@code bareIdLoadsOnOrgOwnedReposRequireGuard} ratchet, so the build will
 * not catch a misuse. It is safe today for exactly one reason, and it is not a
 * general licence: <strong>its sole caller is
 * {@code PillarCourseMappingService}, which is reachable only through
 * {@code PillarController}'s {@code SUPER_ADMIN} gate.</strong> Any caller
 * reachable by an org-scoped role must add its own predicate or use a different
 * query — the catalog's own "published courses are visible to everyone" rule
 * says nothing about DRAFT or ARCHIVED rows, which this returns too.
 *
 * <p><strong>Why raw SQL rather than {@code CourseRepository}.</strong> The
 * ArchUnit ratchet ({@code noCrossFeatureDependencies}) forbids new
 * feature -> feature edges and the frozen-violations store is never written, so
 * — following {@code insights.BenchmarkReadRepository} and
 * {@code common.gdpr.PersonalDataRepository} — this depends on the SCHEMA and
 * imports no catalog type.
 *
 * <p>ponytail: one batched lookup, no cache. A pillar carries a handful of
 * rules, so this is one {@code IN} query per request; add caching only if a
 * profile ever says so.
 */
@Repository
public class CourseCatalogReadRepository {

    /** A course as the mapping surface needs it: what it is called, and whether it is live. */
    public record CourseRef(UUID id, String title, String state) {}

    private final NamedParameterJdbcTemplate jdbc;

    public CourseCatalogReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The requested courses that actually exist, keyed by id — <strong>any
     * course, at any state, in any org.</strong> The name says {@code Unscoped}
     * because that is the contract, not an implementation detail: see the class
     * doc for the single reason it is safe.
     *
     * <p>Ids with no row are simply absent — that absence is how the service
     * both rejects a mapping to a course that does not exist and reports a
     * mapping whose course has since been deleted.
     */
    public Map<UUID, CourseRef> findByIdsUnscoped(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, CourseRef> byId = new LinkedHashMap<>();
        jdbc.query("SELECT id, title, state FROM course WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    byId.put(id, new CourseRef(id, rs.getString("title"), rs.getString("state")));
                });
        return byId;
    }
}
