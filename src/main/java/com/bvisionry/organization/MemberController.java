package com.bvisionry.organization;

import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.organization.dto.BulkChangeMemberStatusRequest;
import com.bvisionry.organization.dto.BulkMemberIdsRequest;
import com.bvisionry.organization.dto.ChangeMemberRoleRequest;
import com.bvisionry.organization.dto.ChangeMemberStatusRequest;
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
