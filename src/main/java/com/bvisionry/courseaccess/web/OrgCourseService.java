package com.bvisionry.courseaccess.web;

import com.bvisionry.common.coursevisibility.CourseVisibilityAccess;
import com.bvisionry.common.enums.EnrollmentSource;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.courseaccess.domain.OrgCourseRule;
import com.bvisionry.courseaccess.dto.AssignCourseRequest;
import com.bvisionry.courseaccess.dto.CatalogCourseView;
import com.bvisionry.courseaccess.dto.MemberCourseView;
import com.bvisionry.courseaccess.dto.OrgCourseRow;
import com.bvisionry.courseaccess.dto.UpdateOrgCourseRequest;
import com.bvisionry.courseaccess.repository.CourseAccessReadRepository;
import com.bvisionry.courseaccess.repository.CourseAccessReadRepository.OrgSourceAggregate;
import com.bvisionry.courseaccess.repository.CourseAssignmentWriteRepository;
import com.bvisionry.courseaccess.repository.OrgCourseRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The org admin's Courses tab (spec §2.3): course × audience × source ×
 * completion, plus assign / override / remove.
 *
 * <p><strong>Every write is gated on VISIBILITY</strong> (spec §3: "org admins
 * can only see and assign the visible set"). Reads are not: an assignment made
 * while a course was visible stays on the tab after the platform pulls it, with
 * {@code visible = false}, because an admin who cannot see a row cannot remove
 * it — and the downgrade policy is "keep progress, never delete data".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrgCourseService {

    private static final String AUDIENCE_RULE = "Everyone (auto, incl. new members)";

    private final OrgCourseRuleRepository rules;
    private final CourseAccessReadRepository reads;
    private final CourseAssignmentWriteRepository writes;
    private final CourseAccessService courseAccess;
    private final CourseVisibilityAccess visibility;
    // For removeForEveryone's removed_by stamp only; every other write takes the
    // actor from the controller. The controller's DELETE signature carries no
    // actor, and the override rows below deserve one.
    private final CurrentUserAccessor currentUser;

    /* ------------------------------------------------------------------ read */

    @Transactional(readOnly = true)
    public List<OrgCourseRow> list(UUID orgId) {
        var orgRules = reads.orgRules(orgId);
        List<OrgSourceAggregate> aggregates = reads.orgEnrolmentAggregates(orgId);
        Map<UUID, Integer> completions = reads.orgCompletionsByCourse(orgId);
        Map<UUID, Integer> exclusions = reads.orgExclusionsByCourse(orgId);
        Map<UUID, Integer> pending = reads.orgPendingSuggestionsByCourse(orgId);
        int members = reads.orgMemberCount(orgId);

        Set<UUID> courseIds = new LinkedHashSet<>();
        orgRules.forEach(r -> courseIds.add(r.courseId()));
        aggregates.forEach(a -> courseIds.add(a.courseId()));
        pending.keySet().forEach(courseIds::add);
        var meta = reads.courseMeta(courseIds);
        Set<UUID> visible = visibility.filterVisible(orgId, courseIds);

        List<OrgCourseRow> rows = new ArrayList<>();

        for (var rule : orgRules) {
            var course = meta.get(rule.courseId());
            if (course == null) {
                continue;
            }
            // Covered = everyone in the org minus the opt-outs. Completion is
            // over the covered set, capped: a member who opted out after
            // finishing would otherwise push the bar past 100%.
            int excluded = exclusions.getOrDefault(rule.courseId(), 0);
            int covered = Math.max(0, members - excluded);
            int completed = Math.min(covered, completions.getOrDefault(rule.courseId(), 0));
            rows.add(new OrgCourseRow(rule.courseId(), course.title(),
                    EnrollmentSource.ORG_RULE,
                    excluded == 0 ? AUDIENCE_RULE : AUDIENCE_RULE + " · " + excluded + " opted out",
                    rule.required(), rule.deadline(), covered, completed, pct(completed, covered),
                    pending.getOrDefault(rule.courseId(), 0),
                    visible.contains(rule.courseId()),
                    rule.createdAt(), rule.createdByName()));
        }

        for (OrgSourceAggregate agg : aggregates) {
            // Materialized org-rule enrollments are already counted by the rule
            // row above; a second row would double-count the same assignment.
            if (agg.source() == EnrollmentSource.ORG_RULE) {
                continue;
            }
            var course = meta.get(agg.courseId());
            if (course == null) {
                continue;
            }
            rows.add(new OrgCourseRow(agg.courseId(), course.title(), agg.source(),
                    audienceOf(agg), agg.required(), agg.deadline(),
                    agg.learners(), agg.completed(), pct(agg.completed(), agg.learners()),
                    agg.source() == EnrollmentSource.AI_SUGGESTED
                            ? pending.getOrDefault(agg.courseId(), 0) : 0,
                    visible.contains(agg.courseId()),
                    agg.assignedAt(), agg.assignedByName()));
        }

        rows.sort((a, b) -> a.courseTitle().compareToIgnoreCase(b.courseTitle()));
        return List.copyOf(rows);
    }

    /**
     * The courses this org may assign from (spec §3): PUBLISHED and visible.
     *
     * <p>Its own endpoint rather than a filter on the public catalog, because
     * the catalog is a marketing surface with no org in hand — and because a
     * picker that offers a course the assign call will refuse is worse than no
     * picker.
     */
    @Transactional(readOnly = true)
    public List<CatalogCourseView> assignable(UUID orgId) {
        return reads.visibleCatalog(orgId).stream()
                .map(r -> new CatalogCourseView(r.courseId(), r.title(), r.slug(),
                        r.category(), r.level(), r.lessonsCount()))
                .toList();
    }

    /** The per-member drill-in the founder-profile Work tab and the assign dialog use. */
    @Transactional(readOnly = true)
    public List<MemberCourseView> coursesOfMember(UUID orgId, UUID memberId) {
        requireMember(orgId, memberId);
        Instant now = Instant.now();
        return courseAccess.effectiveCoursesOf(memberId, orgId).stream()
                .map(c -> CourseAccessService.toView(c, now))
                .toList();
    }

    /* ----------------------------------------------------------------- write */

    /**
     * Assign (spec §3). {@code ORG} writes ONE rule that covers every current and
     * future member; {@code MEMBERS} writes a DIRECT enrollment per selected
     * member and clears any previous exclusion — assigning someone by name today
     * outranks having removed them last month.
     */
    @Transactional
    public void assign(UUID orgId, AssignCourseRequest request, UUID actorId) {
        requireVisible(orgId, request.courseId());

        if (AssignCourseRequest.AUDIENCE_ORG.equalsIgnoreCase(request.audience())) {
            OrgCourseRule rule = rules.findByOrgIdAndCourseId(orgId, request.courseId())
                    .orElseGet(OrgCourseRule::new);
            rule.setOrgId(orgId);
            rule.setCourseId(request.courseId());
            rule.setRequired(request.required());
            rule.setDeadline(request.deadline());
            // Who first made the org's curation decision is not rewritten by a
            // re-assign; the §7b stamp the tab shows is the LATEST of the two.
            if (rule.getCreatedBy() == null) {
                rule.setCreatedBy(actorId);
            }
            rule.setUpdatedBy(actorId);
            // saveAndFlush: every read on this surface is raw SQL (ArchUnit),
            // so an unflushed JPA insert is invisible to the very list the
            // caller reloads next.
            rules.saveAndFlush(rule);
            log.info("Org {} course {} assigned org-wide (required={}) by {}",
                    orgId, request.courseId(), request.required(), actorId);
            return;
        }

        if (!AssignCourseRequest.AUDIENCE_MEMBERS.equalsIgnoreCase(request.audience())) {
            throw new BadRequestException("audience must be ORG or MEMBERS");
        }
        List<UUID> targets = writes.membersIn(orgId,
                request.memberIds() == null ? List.of() : request.memberIds());
        if (targets.isEmpty()) {
            throw new BadRequestException("Select at least one member of this organization");
        }
        for (UUID memberId : targets) {
            writes.clearExclusion(memberId, request.courseId());
            writes.upsert(memberId, request.courseId(), EnrollmentSource.DIRECT,
                    request.required(), request.deadline(), actorId);
        }
        log.info("Org {} course {} assigned directly to {} member(s) by {}",
                orgId, request.courseId(), targets.size(), actorId);
    }

    /** Spec §11: convert to optional / required, and re-date, after the fact. */
    @Transactional
    public void update(UUID orgId, UUID courseId, UpdateOrgCourseRequest request) {
        // strictOf, not of: of()'s degrade-to-SELF is for stored columns, and on
        // a write path it would silently retarget the update at self-enrolments.
        EnrollmentSource source = EnrollmentSource.strictOf(request.source());
        if (source == EnrollmentSource.ORG_RULE) {
            OrgCourseRule rule = rules.findByOrgIdAndCourseId(orgId, courseId)
                    .orElseThrow(() -> new ResourceNotFoundException("Org course rule", courseId.toString()));
            rule.setRequired(request.required());
            rule.setDeadline(request.deadline());
            rules.saveAndFlush(rule);
            return;
        }
        if (writes.setRequiredAndDeadline(orgId, courseId, source,
                request.required(), request.deadline()) == 0) {
            throw new ResourceNotFoundException("Course assignment", courseId.toString());
        }
    }

    /**
     * "Remove for everyone" — delete the rule, or cancel every enrollment of
     * that source in this org. Never a DELETE of enrollment rows: the status
     * flip keeps progress, certificates and content progress intact.
     *
     * <p><strong>The override rows are what make the removal HOLD</strong>
     * (operator decision 2026-08-14). A CANCELLED row alone is one click from
     * undone: the member's self-enrol path
     * ({@code EnrollmentService#reactivateIfRemoved}) restores any CANCELLED row
     * that has no {@code enrolment_overrides} row saying an admin removed it. So
     * this writes the same removed-by-admin row {@link #removeForMember} writes,
     * once per member the cancel is about to hit — and, like every exclusion, it
     * is member-level, sticky, and beats any org rule still standing until an
     * explicit by-name assign clears it.
     */
    @Transactional
    public void removeForEveryone(UUID orgId, UUID courseId, String sourceName) {
        // strictOf, not of: of()'s degrade-to-SELF would turn a typo'd source
        // into "cancel every self-enrolment in the org".
        EnrollmentSource source = EnrollmentSource.strictOf(sourceName);
        if (source == EnrollmentSource.ORG_RULE) {
            rules.findByOrgIdAndCourseId(orgId, courseId).ifPresent(r -> {
                rules.delete(r);
                rules.flush();
            });
            // DECIDED: exclusions are STICKY across a rule's delete and re-create.
            // An opt-out is a statement about a PERSON ("not this member"), not
            // about the rule instance that happened to be live when it was made —
            // and clearing it here would silently re-add someone an admin removed
            // the moment the rule was re-assigned. Same reason a re-assign does
            // not clear them either.
            //
            // "Unassignment is one delete" must hold for everyone, so the members
            // who already OPENED the course are cancelled too — their row is a
            // materialization of this rule, not a separate claim. Progress,
            // content_progress and certificates survive the status flip.
            log.info("Org {} course {} rule removed for everyone", orgId, courseId);
        }
        // Order matters: the exclusion insert selects the LIVE enrollments the
        // cancel below is about to flip.
        writes.excludeAllEnrolled(orgId, courseId, source, currentUser.require().userId());
        writes.cancelForOrg(orgId, courseId, source);
        log.info("Org {} course {} ({}) cancelled for everyone", orgId, courseId, source);
    }

    /**
     * Remove for ONE member — the founder-profile Work tab's override (spec
     * §2.4). Writes the exclusion row (member-level beats org-level) AND cancels
     * any real enrollment, so it works whatever the source was.
     */
    @Transactional
    public void removeForMember(UUID orgId, UUID memberId, UUID courseId, String reason, UUID actorId) {
        requireMember(orgId, memberId);
        writes.exclude(memberId, courseId, actorId, reason);
        writes.cancel(memberId, courseId);
        log.info("Org {} member {} excluded from course {} by {}", orgId, memberId, courseId, actorId);
    }

    /* ---------------------------------------------------------------- helpers */

    private void requireVisible(UUID orgId, UUID courseId) {
        // PUBLISHED *and* visible — same predicate the member's Accept uses, so a
        // course an admin can assign is always one a member can take up.
        if (!visibility.isAssignable(courseId, orgId)) {
            throw new BadRequestException("This course is not available to your organization");
        }
    }

    private void requireMember(UUID orgId, UUID memberId) {
        if (!reads.isMemberOf(orgId, memberId)) {
            throw new ResourceNotFoundException("Member", memberId.toString());
        }
    }

    private static String audienceOf(OrgSourceAggregate agg) {
        return switch (agg.source()) {
            case DIRECT -> agg.learners() + (agg.learners() == 1 ? " selected member" : " selected members");
            case AI_SUGGESTED -> agg.learners() + " from pillar rules";
            case SELF -> agg.learners() + " self-enrolled";
            case ORG_RULE -> AUDIENCE_RULE;
        };
    }

    private static int pct(int done, int total) {
        return total == 0 ? 0 : (int) Math.round(done * 100.0 / total);
    }
}
