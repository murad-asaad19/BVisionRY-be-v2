package com.bvisionry.organization;

import com.bvisionry.common.audit.AuditLogger;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.organization.dto.MemberCourseResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "An admin can override" — the half of roadmap §7 item 10 the auto-enrolment
 * engine shipped without.
 *
 * <p>Until this existed, an automatic enrolment was permanent: the engine's own
 * {@code FounderEnrolmentWriteRepository} is INSERT-only by design, and
 * {@code EnrollmentController} exposes self-enrol and mark-complete and nothing
 * else. Once a founder's evaluation put them on a course, neither they nor their
 * org admin could take them off it.
 *
 * <p><strong>Its own class rather than two more methods on
 * {@link MemberService}.</strong> Not taste — that class's eight-parameter
 * constructor is spelled out verbatim in eight frozen ArchUnit violations, so a
 * ninth parameter re-describes all eight and reports them as new, and the only
 * "fix" would be adding lines to a store that is append-forbidden. Here the
 * dependencies are same-package or {@code common} ports
 * ({@link AuditLogger}), both of which the ratchet exempts, so nothing is frozen
 * and nothing is smuggled.
 *
 * <p><strong>Tenancy is {@link MemberService#getMember}, reused rather than
 * reimplemented.</strong> The controller's class-level
 * {@code @orgAccess.isInOrg(#orgId)} proves the CALLER may administer this org;
 * that call proves the MEMBER in the path belongs to it. Both are needed — an org
 * admin passing their own orgId with a stranger's member id satisfies the first
 * and is stopped only by the second — and the bare-ID load behind it stays inside
 * the one guard the frozen store already reviewed.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MemberCourseService {

    /** Matches {@code UnlockPillarsRequest#reason} — the codebase's other admin note. */
    private static final int MAX_REASON_LENGTH = 500;

    private final MemberService memberService;
    private final OrganizationService organizationService;
    private final MemberCourseRepository memberCourses;
    private final AuditLogger auditLogger;

    /**
     * The member's courses, with the pillar that auto-enrolled them where there was
     * one. The surface the remove control hangs off.
     */
    @Transactional(readOnly = true)
    public List<MemberCourseResponse> listCourses(UUID orgId, UUID memberId) {
        memberService.getMember(orgId, memberId);
        return memberCourses.findCoursesFor(memberId);
    }

    /**
     * Take the member off the course, and keep them off it.
     *
     * <p>Durable against re-assessment, which is the whole acceptance criterion:
     * {@code enrolment_overrides} (V157) is keyed (user, course) with NO submission
     * id, and {@code AutoEnrolmentService} consults it before its per-evaluation
     * idempotency read. A later assessment carries a new submission id, sees an
     * empty ledger for it, and still skips the course.
     *
     * <p>The founder's progress is detached, not destroyed — see
     * {@link MemberCourseRepository#removeFromCourse}.
     */
    @Transactional
    public void removeFromCourse(UUID orgId, UUID memberId, UUID courseId,
                                  String reason, UUID actorId) {
        // Free text from a trust boundary into an unbounded TEXT column. Same cap
        // and same rejection-over-truncation choice as UnlockPillarsRequest's
        // reason: a silently shortened explanation is worse than a rejected one.
        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            throw new BadRequestException("Reason is too long");
        }
        organizationService.findActiveOrThrow(orgId);
        memberService.getMember(orgId, memberId);

        if (!memberCourses.hasEnrolment(memberId, courseId)) {
            throw new ResourceNotFoundException("Enrollment", courseId.toString());
        }
        boolean cancelled = memberCourses.removeFromCourse(memberId, courseId, actorId, reason);

        // Audited even when the enrolment was already cancelled: the override is
        // re-asserted either way, and "who tried" is what an activity feed is for.
        // `alreadyRemoved` keeps the two apart for whoever reads it back.
        Map<String, Object> details = new HashMap<>();
        details.put("courseId", courseId.toString());
        details.put("alreadyRemoved", String.valueOf(!cancelled));
        if (reason != null && !reason.isBlank()) {
            details.put("reason", reason);
        }
        auditLogger.log(actorId, orgId, OrgAuditActions.MEMBER_COURSE_REMOVED,
                OrgAuditActions.ENTITY_USER, memberId, details);

        log.info("Removed member {} from course {} in org {} (was live: {})",
                memberId, courseId, orgId, cancelled);
    }
}
