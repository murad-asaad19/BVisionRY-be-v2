package com.bvisionry.courseaccess.web;

import com.bvisionry.common.coursevisibility.OrgCourseVisibility;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.courseaccess.dto.CourseVisibilityView;
import com.bvisionry.courseaccess.dto.OrgOptionView;
import com.bvisionry.courseaccess.dto.UpdateCourseVisibilityRequest;
import com.bvisionry.courseaccess.repository.CourseAccessReadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The platform Course visibility screen (spec §2.5, §3): which organizations can
 * see and assign each course. Super admin only — members never see this.
 *
 * <p>Writes go through JDBC rather than the {@code Course} entity for the
 * ArchUnit reason (no {@code courseaccess} → {@code catalog} edge) and because
 * they touch four columns of a 30-column aggregate: an entity round-trip here
 * would risk clobbering an authoring edit made in the same second.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CourseVisibilityService {

    private final CourseAccessReadRepository reads;
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<CourseVisibilityView> list() {
        return reads.allCourseVisibility().stream()
                .map(r -> new CourseVisibilityView(r.courseId(), r.title(), r.category(),
                        r.lessonsCount(), r.state(), r.visibility(), r.minTier(), r.orgIds(),
                        r.updatedAt(), r.updatedByName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrgOptionView> organizations() {
        return reads.assignableOrgs().stream()
                .map(o -> new OrgOptionView(o.orgId(), o.name()))
                .toList();
    }

    /** The tiers the MIN_TIER picker offers, cheapest first — the enum IS the ranking. */
    public List<String> tiers() {
        return java.util.Arrays.stream(SubscriptionTier.values()).map(Enum::name).toList();
    }

    @Transactional
    public void update(UUID courseId, UpdateCourseVisibilityRequest request, UUID actorId) {
        OrgCourseVisibility mode = parse(request.visibility());
        String minTier = mode == OrgCourseVisibility.MIN_TIER ? requireTier(request.minTier()) : null;

        int updated = jdbc.update("""
                UPDATE course
                   SET org_visibility = :mode,
                       org_visibility_min_tier = :minTier,
                       org_visibility_updated_at = :now,
                       org_visibility_updated_by = :actorId
                 WHERE id = :courseId
                """,
                new MapSqlParameterSource("courseId", courseId)
                        .addValue("mode", mode.name())
                        .addValue("minTier", minTier)
                        .addValue("now", Timestamp.from(Instant.now()))
                        .addValue("actorId", actorId));
        if (updated == 0) {
            throw new BadRequestException("Course not found");
        }

        // The explicit list is replaced wholesale — a partial PUT would leave an
        // org visible that the admin just unticked, which is the failure mode
        // this screen exists to prevent.
        jdbc.update("DELETE FROM course_visible_orgs WHERE course_id = :courseId",
                new MapSqlParameterSource("courseId", courseId));
        if (mode == OrgCourseVisibility.ORG_LIST) {
            List<UUID> orgIds = request.orgIds() == null ? List.of() : request.orgIds();
            for (UUID orgId : orgIds) {
                jdbc.update("""
                        INSERT INTO course_visible_orgs (course_id, org_id) VALUES (:courseId, :orgId)
                        ON CONFLICT DO NOTHING
                        """,
                        new MapSqlParameterSource("courseId", courseId).addValue("orgId", orgId));
            }
        }
        log.info("Course {} visibility set to {} ({}) by {}", courseId, mode, minTier, actorId);
    }

    private static OrgCourseVisibility parse(String value) {
        for (OrgCourseVisibility v : OrgCourseVisibility.values()) {
            if (v.name().equalsIgnoreCase(value)) {
                return v;
            }
        }
        throw new BadRequestException("visibility must be EVERYONE, MIN_TIER or ORG_LIST");
    }

    private static String requireTier(String value) {
        for (SubscriptionTier tier : SubscriptionTier.values()) {
            if (tier.name().equalsIgnoreCase(value)) {
                return tier.name();
            }
        }
        throw new BadRequestException("minTier is required for MIN_TIER visibility");
    }
}
