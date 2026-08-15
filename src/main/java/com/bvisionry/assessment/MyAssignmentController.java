package com.bvisionry.assessment;

import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.assessment.dto.AssessmentSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Member-facing endpoints scoped to a specific Assignment (rather than an
 * individual Submission). Currently exposes the "start a new check-in"
 * action for assignments configured with {@code maxCheckIns > 1}.
 */
@RestController
@RequestMapping("/api/my/assignments")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MyAssignmentController {

    private final CurrentUserAccessor currentUser;
    private final AssignmentService assignmentService;

    @PostMapping("/{assignmentId}/check-ins")
    public ResponseEntity<AssessmentSummaryResponse> startNewCheckIn(@PathVariable UUID assignmentId) {
        AssessmentSummaryResponse summary =
                assignmentService.startNewCheckIn(assignmentId, currentUser.require().userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(summary);
    }
}
