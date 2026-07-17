package com.bvisionry.exercise;

import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.exercise.dto.ExerciseCommentResponse;
import com.bvisionry.exercise.dto.ExerciseSubmissionDetailResponse;
import com.bvisionry.exercise.dto.MyExerciseSummaryResponse;
import com.bvisionry.exercise.dto.ReplyExerciseCommentRequest;
import com.bvisionry.exercise.dto.SaveExerciseRowsRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Member-facing exercise endpoints. User identity comes from the JWT
 * security context, mirroring {@code /api/my/assessments}.
 */
@RestController
@RequestMapping("/api/my/exercises")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MyExerciseController {

    private final ExerciseSubmissionService submissionService;

    @GetMapping
    public ResponseEntity<List<MyExerciseSummaryResponse>> listMine() {
        return ResponseEntity.ok(submissionService.listMine(SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/{submissionId}")
    public ResponseEntity<ExerciseSubmissionDetailResponse> get(@PathVariable UUID submissionId) {
        return ResponseEntity.ok(
                submissionService.getForMember(submissionId, SecurityUtils.getCurrentUserId()));
    }

    /** Debounced autosave target — replace-all rows, allowed in every status. */
    @PutMapping("/{submissionId}/rows")
    public ResponseEntity<ExerciseSubmissionDetailResponse> saveRows(
            @PathVariable UUID submissionId,
            @Valid @RequestBody SaveExerciseRowsRequest request) {
        return ResponseEntity.ok(
                submissionService.saveRows(submissionId, SecurityUtils.getCurrentUserId(), request));
    }

    @PostMapping("/{submissionId}/submit")
    public ResponseEntity<ExerciseSubmissionDetailResponse> submit(@PathVariable UUID submissionId) {
        return ResponseEntity.ok(
                submissionService.submit(submissionId, SecurityUtils.getCurrentUserId()));
    }

    @PostMapping("/{submissionId}/comments/{commentId}/reply")
    public ResponseEntity<ExerciseCommentResponse> reply(
            @PathVariable UUID submissionId,
            @PathVariable UUID commentId,
            @Valid @RequestBody ReplyExerciseCommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(submissionService.reply(
                submissionId, SecurityUtils.getCurrentUserId(), commentId, request.body()));
    }
}
