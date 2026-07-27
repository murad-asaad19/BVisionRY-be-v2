package com.bvisionry.organization;

import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.organization.dto.BulkChangeMemberStatusRequest;
import com.bvisionry.organization.dto.BulkMemberIdsRequest;
import com.bvisionry.organization.dto.ChangeMemberRoleRequest;
import com.bvisionry.organization.dto.ChangeMemberStatusRequest;
import com.bvisionry.organization.dto.MemberCourseResponse;
import com.bvisionry.organization.dto.MemberResponse;
import com.bvisionry.organization.dto.RemoveMemberResponse;
import com.bvisionry.organization.dto.UpdateMemberProfileRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{orgId}/members")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
public class MemberController {

    private final MemberService memberService;
    private final MemberCourseService memberCourseService;

    /**
     * The {@code common} port for "who is calling", not {@code auth.SecurityUtils}.
     * The existing handlers below keep their {@code SecurityUtils} calls — those
     * call sites are frozen ArchUnit violations — but each NEW one would be a new
     * violation of the cross-feature ratchet, which the port exists to avoid.
     */
    private final CurrentUserAccessor currentUser;

    @GetMapping
    public ResponseEntity<List<MemberResponse>> listMembers(@PathVariable UUID orgId) {
        return ResponseEntity.ok(memberService.listMembers(orgId));
    }

    @GetMapping("/{memberId}")
    public ResponseEntity<MemberResponse> getMember(@PathVariable UUID orgId, @PathVariable UUID memberId) {
        return ResponseEntity.ok(memberService.getMember(orgId, memberId));
    }

    @PatchMapping("/{memberId}/status")
    public ResponseEntity<MemberResponse> changeStatus(@PathVariable UUID orgId,
                                                        @PathVariable UUID memberId,
                                                        @Valid @RequestBody ChangeMemberStatusRequest request) {
        UUID actorId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(memberService.changeStatus(orgId, memberId, request, actorId));
    }

    @PatchMapping("/{memberId}/role")
    public ResponseEntity<MemberResponse> changeRole(@PathVariable UUID orgId,
                                                      @PathVariable UUID memberId,
                                                      @Valid @RequestBody ChangeMemberRoleRequest request) {
        UUID actorId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(memberService.changeRole(orgId, memberId, request, actorId));
    }

    /**
     * Update a member's lightweight profile (name, member type) from inside
     * the org admin UI. Lives here rather than on /api/users/{id} so ORG_ADMINs
     * can edit their own org's members — the user-level PUT is super-admin only.
     */
    @PatchMapping("/{memberId}/profile")
    public ResponseEntity<MemberResponse> updateProfile(@PathVariable UUID orgId,
                                                         @PathVariable UUID memberId,
                                                         @Valid @RequestBody UpdateMemberProfileRequest request) {
        UUID actorId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(memberService.updateProfile(orgId, memberId, request, actorId));
    }

    /**
     * The member's courses, with the pillar that auto-enrolled them where there was
     * one. The list the "remove from course" control hangs off.
     */
    @GetMapping("/{memberId}/courses")
    public ResponseEntity<List<MemberCourseResponse>> listCourses(@PathVariable UUID orgId,
                                                                   @PathVariable UUID memberId) {
        return ResponseEntity.ok(memberCourseService.listCourses(orgId, memberId));
    }

    /**
     * Override an auto-enrolment: take the member off the course and keep them off
     * it, including through any future assessment (roadmap §7 item 10).
     *
     * <p><strong>No method-level {@code @PreAuthorize}, deliberately.</strong>
     * Spring method security REPLACES a class-level annotation with a method-level
     * one — it never ANDs them — so adding one here would silently delete this
     * class's {@code @orgAccess.isInOrg(#orgId)} tenancy gate, which is the last
     * thing a delete endpoint can afford. The class gate proves the caller may
     * administer this org; {@code MemberService} proves the member belongs to it.
     */
    @DeleteMapping("/{memberId}/courses/{courseId}")
    public ResponseEntity<Void> removeFromCourse(@PathVariable UUID orgId,
                                                   @PathVariable UUID memberId,
                                                   @PathVariable UUID courseId,
                                                   @RequestParam(required = false) String reason) {
        memberCourseService.removeFromCourse(orgId, memberId, courseId, reason,
                currentUser.require().userId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{memberId}/responses")
    public ResponseEntity<Void> clearResponses(@PathVariable UUID orgId,
                                                @PathVariable UUID memberId) {
        UUID actorId = SecurityUtils.getCurrentUserId();
        memberService.clearResponses(orgId, memberId, actorId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bulk/status")
    public ResponseEntity<List<MemberResponse>> bulkChangeStatus(@PathVariable UUID orgId,
                                                                  @Valid @RequestBody BulkChangeMemberStatusRequest request) {
        UUID actorId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(memberService.bulkChangeStatus(orgId, request.memberIds(), request.status(), actorId));
    }

    @PostMapping("/bulk/clear-responses")
    public ResponseEntity<Void> bulkClearResponses(@PathVariable UUID orgId,
                                                    @Valid @RequestBody BulkMemberIdsRequest request) {
        UUID actorId = SecurityUtils.getCurrentUserId();
        memberService.bulkClearResponses(orgId, request.memberIds(), actorId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{memberId}")
    public ResponseEntity<RemoveMemberResponse> removeMember(
            @PathVariable UUID orgId,
            @PathVariable UUID memberId,
            @RequestParam(name = "wipeAssessments", defaultValue = "false") boolean wipeAssessments) {
        UUID actorId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(
                memberService.removeMember(orgId, memberId, wipeAssessments, actorId));
    }

    /**
     * Hard delete: permanently erase the user row (vs the anonymize-in-place
     * {@code DELETE /{memberId}}). Their personal data cascades away and the
     * email is freed, so the person can sign up or be invited again as a
     * brand-new account.
     */
    @DeleteMapping("/{memberId}/permanent")
    public ResponseEntity<Void> deleteMemberPermanently(@PathVariable UUID orgId,
                                                         @PathVariable UUID memberId) {
        UUID actorId = SecurityUtils.getCurrentUserId();
        memberService.deleteMemberPermanently(orgId, memberId, actorId);
        return ResponseEntity.noContent().build();
    }
}
