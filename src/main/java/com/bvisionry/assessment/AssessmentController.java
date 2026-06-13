package com.bvisionry.assessment;

import com.bvisionry.assessment.dto.AnswerResponse;
import com.bvisionry.assessment.dto.AssessmentDetailResponse;
import com.bvisionry.assessment.dto.AssessmentSummaryResponse;
import com.bvisionry.assessment.dto.BatchSaveAnswersRequest;
import com.bvisionry.assessment.dto.ReviewResponse;
import com.bvisionry.assessment.dto.SaveAnswerRequest;
import com.bvisionry.assessment.dto.SubmissionStatusResponse;
import com.bvisionry.assessment.dto.SubmitAssessmentResponse;
import com.bvisionry.auth.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
 * Member-facing assessment flow endpoints.
 * User identity is extracted from SecurityContext (JWT authentication).
 */
@RestController
@RequestMapping("/api/my/assessments")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class AssessmentController {

    private final AssessmentService assessmentService;

    @GetMapping
    public ResponseEntity<List<AssessmentSummaryResponse>> listAssessments() {
        return ResponseEntity.ok(assessmentService.listAssessments(SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/{submissionId}")
    public ResponseEntity<AssessmentDetailResponse> getAssessment(@PathVariable UUID submissionId) {
        return ResponseEntity.ok(assessmentService.getAssessment(submissionId, SecurityUtils.getCurrentUserId()));
    }

    @PutMapping("/{submissionId}/answers/{questionId}")
    public ResponseEntity<AnswerResponse> saveAnswer(
            @PathVariable UUID submissionId,
            @PathVariable UUID questionId,
            @Valid @RequestBody SaveAnswerRequest request) {
        return ResponseEntity.ok(
                assessmentService.saveAnswer(submissionId, questionId, SecurityUtils.getCurrentUserId(), request));
    }

    @PostMapping("/{submissionId}/answers/batch")
    public ResponseEntity<List<AnswerResponse>> batchSaveAnswers(
            @PathVariable UUID submissionId,
            @Valid @RequestBody BatchSaveAnswersRequest request) {
        return ResponseEntity.ok(
                assessmentService.batchSaveAnswers(submissionId, SecurityUtils.getCurrentUserId(), request));
    }

    @GetMapping("/{submissionId}/review")
    public ResponseEntity<ReviewResponse> getReview(@PathVariable UUID submissionId) {
        return ResponseEntity.ok(assessmentService.getReview(submissionId, SecurityUtils.getCurrentUserId()));
    }

    @PostMapping("/{submissionId}/submit")
    public ResponseEntity<SubmitAssessmentResponse> submitAssessment(@PathVariable UUID submissionId) {
        return ResponseEntity.ok(assessmentService.submitAssessment(submissionId, SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/{submissionId}/status")
    public ResponseEntity<SubmissionStatusResponse> getStatus(@PathVariable UUID submissionId) {
        return ResponseEntity.ok(assessmentService.getStatus(submissionId, SecurityUtils.getCurrentUserId()));
    }
}
