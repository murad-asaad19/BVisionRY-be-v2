package com.bvisionry.programflow.web;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.common.orgmember.OrgMemberAccess;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Staff-side "start this member's exercise": an EXERCISE cohort task the member
 * never opened has no working copy, so there is nothing for the review screen to
 * show and nothing to edit. This materializes that copy on demand — the same
 * idempotent write the member's own open performs — and answers with the
 * assignment id to navigate to.
 *
 * <p>SUPER_ADMIN only, matching the sibling answer/status overrides in
 * {@code ExerciseAssignmentController}: writing work on a member's behalf is
 * the same authority, and merely READING an unstarted exercise needs no copy.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Member exercise copy (admin)",
        description = "Materialize a member's exercise working copy for an unstarted cohort task.")
public class MemberExerciseCopyController {

    private final ProgramAdminService service;
    private final OrgMemberAccess orgMembers;

    /** @return the exercise assignment id the review screen is keyed on. */
    @PostMapping(
            path = "/api/organizations/{orgId}/members/{memberId}/program-tasks/{taskId}/exercise-copy",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public MemberExerciseCopyResponse ensureCopy(@PathVariable UUID orgId,
            @PathVariable UUID memberId, @PathVariable UUID taskId) {
        // Same reason as MemberTaskViewController: the role guard proves the
        // caller may act, the member guard proves this member is theirs to act on.
        orgMembers.requireMemberOf(orgId, memberId);
        return new MemberExerciseCopyResponse(
                service.ensureMemberExerciseCopy(orgId, memberId, taskId));
    }

    /** @param assignmentId the member's exercise assignment for this task. */
    public record MemberExerciseCopyResponse(UUID assignmentId) {}
}
