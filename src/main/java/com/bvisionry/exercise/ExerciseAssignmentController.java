package com.bvisionry.exercise;

import com.bvisionry.exercise.dto.CreateExerciseAssignmentRequest;
import com.bvisionry.exercise.dto.CreateExerciseCommentRequest;
import com.bvisionry.exercise.dto.ExerciseAssignmentResponse;
import com.bvisionry.exercise.dto.ExerciseCommentResponse;
import com.bvisionry.exercise.dto.ExerciseSubmissionDetailResponse;
import com.bvisionry.exercise.dto.SetQualityTagRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Org console for exercises: distribute templates to members and run the
 * review loop (read the member's sheet, comment, resolve, request changes,
 * mark reviewed).
 *
 * <p>Distribution stays admin-only (the class annotation). The five review
 * endpoints additionally admit a COACH of the same org — see
 * {@link #REVIEW_ACCESS} — whose reach is then narrowed to their assigned
 * founders at the data layer ({@code ExerciseReviewService} checks the
 * assignment union via {@code CoachAccess} and 404s outside it).
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/exercise-assignments")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
public class ExerciseAssignmentController {

    /** Review-loop access: admins of the org, or a coach inside it. */
    private static final String REVIEW_ACCESS = "hasAuthority('SUPER_ADMIN') "
            + "or ((hasAuthority('ORG_ADMIN') or hasAuthority('COACH')) and @orgAccess.isInOrg(#orgId))";

    private final ExerciseAssignmentService assignmentService;
    private final ExerciseReviewService reviewService;

    @PostMapping
    public ResponseEntity<List<ExerciseAssignmentResponse>> createAssignment(
            @PathVariable UUID orgId,
            @Valid @RequestBody CreateExerciseAssignmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(assignmentService.createAssignment(orgId, request));
    }

    @GetMapping
    public ResponseEntity<List<ExerciseAssignmentResponse>> listAssignments(
            @PathVariable UUID orgId,
            @RequestParam(required = false) ExerciseAssignmentService.ExerciseAssignmentListScope scope) {
        return ResponseEntity.ok(assignmentService.listAssignments(orgId, scope));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelAssignment(
            @PathVariable UUID orgId,
            @PathVariable UUID id) {
        assignmentService.cancelAssignment(orgId, id);
        return ResponseEntity.noContent().build();
    }

    /** The member's full sheet + comment threads for the review screen. */
    @GetMapping("/{id}/submission")
    @PreAuthorize(REVIEW_ACCESS)
    public ResponseEntity<ExerciseSubmissionDetailResponse> getSubmission(
            @PathVariable UUID orgId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(reviewService.getSubmission(orgId, id));
    }

    @PostMapping("/{id}/comments")
    @PreAuthorize(REVIEW_ACCESS)
    public ResponseEntity<ExerciseCommentResponse> addComment(
            @PathVariable UUID orgId,
            @PathVariable UUID id,
            @Valid @RequestBody CreateExerciseCommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reviewService.addComment(orgId, id, request));
    }

    @PatchMapping("/{id}/comments/{commentId}/resolve")
    @PreAuthorize(REVIEW_ACCESS)
    public ResponseEntity<ExerciseCommentResponse> resolveComment(
            @PathVariable UUID orgId,
            @PathVariable UUID id,
            @PathVariable UUID commentId) {
        return ResponseEntity.ok(reviewService.resolveComment(orgId, id, commentId));
    }

    @PostMapping("/{id}/request-changes")
    @PreAuthorize(REVIEW_ACCESS)
    public ResponseEntity<ExerciseSubmissionDetailResponse> requestChanges(
            @PathVariable UUID orgId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(reviewService.requestChanges(orgId, id));
    }

    /** Optional body carries the §4 quality tag chosen alongside the review. */
    @PostMapping("/{id}/mark-reviewed")
    @PreAuthorize(REVIEW_ACCESS)
    public ResponseEntity<ExerciseSubmissionDetailResponse> markReviewed(
            @PathVariable UUID orgId,
            @PathVariable UUID id,
            @RequestBody(required = false) SetQualityTagRequest request) {
        return ResponseEntity.ok(reviewService.markReviewed(orgId, id,
                request == null ? null : request.qualityTagKey()));
    }

    /** Set / change / clear the quality tag on an already reviewed submission (§4). */
    @PatchMapping("/{id}/quality-tag")
    @PreAuthorize(REVIEW_ACCESS)
    public ResponseEntity<ExerciseSubmissionDetailResponse> setQualityTag(
            @PathVariable UUID orgId,
            @PathVariable UUID id,
            @RequestBody SetQualityTagRequest request) {
        return ResponseEntity.ok(reviewService.setQualityTag(orgId, id, request.qualityTagKey()));
    }
}
