package com.bvisionry.catalog.embed;

import com.bvisionry.assessment.AssignmentRepository;
import com.bvisionry.assessment.AssignmentService;
import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.entity.Assignment;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.catalog.domain.Content;
import com.bvisionry.catalog.domain.ContentType;
import com.bvisionry.catalog.repository.ContentRepository;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.enrollment.repository.EnrollmentRepository;
import com.bvisionry.enrollment.web.EnrollmentService;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.repository.PipelineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Resolves and lazily creates the member's assessment submission for an
 * ASSIGNMENT-type lesson embedded in the LMS course player.
 *
 * <p>Two public entry points:
 * <ul>
 *   <li>{@link #startAssessment} — POST; lazily creates Assignment+Submission if none
 *       exists, then returns the link DTO.</li>
 *   <li>{@link #resolveAssessment} — GET; read-only resolve; marks the lesson complete
 *       when the submission is SUBMITTED or EVALUATED.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddedAssessmentService {

    private final ContentRepository contentRepository;
    private final PipelineRepository pipelineRepository;
    private final AssignmentRepository assignmentRepository;
    private final SubmissionRepository submissionRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final EnrollmentService enrollmentService;
    private final AssignmentService assignmentService;
    private final UserRepository userRepository;

    // -------------------------------------------------------------------------
    // POST — start (lazy create + resolve)
    // -------------------------------------------------------------------------

    /**
     * Resolves the member's submission for the pipeline embedded in the given
     * ASSIGNMENT lesson. If no Assignment+Submission exists yet, one is lazily
     * created via {@link AssignmentService#createAssignmentForMember}.
     */
    @Transactional
    public AssessmentLinkDto startAssessment(String slug, UUID contentId) {
        Content content = resolveContent(slug, contentId);
        Pipeline pipeline = resolvePipeline(content);

        UUID userId = SecurityUtils.getCurrentUserId();

        // Security: starting an assessment lazily spawns a real Assignment+Submission
        // and triggers the evaluation/email machinery. Gate it on course enrollment so
        // an authenticated-but-unenrolled user cannot provision assignments (mirrors the
        // enrollment guard in ReviewService#upsert).
        UUID courseId = content.getSection().getCourse().getId();
        if (!enrollmentRepository.existsByUserIdAndCourseId(userId, courseId)) {
            throw new AccessDeniedException("You must be enrolled in this course to start the assessment");
        }

        List<Submission> existing =
                submissionRepository.findByUserIdAndPipelineIdOrderByCreatedAtDesc(userId, pipeline.getId());

        if (!existing.isEmpty()) {
            Submission latest = existing.get(0);
            return AssessmentLinkDto.of(pipeline.getId(), pipeline.getName(),
                    latest.getId(), latest.getStatus());
        }

        // Lazy creation: load user with organization (needed by createAssignmentForMember)
        User currentUser = userRepository.findByIdWithOrganization(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId.toString()));

        if (currentUser.getOrganization() == null) {
            // SUPER_ADMIN or platform user without an org — cannot auto-assign
            log.warn("Cannot lazily create assessment assignment for user {} — no organization", userId);
            return AssessmentLinkDto.notAssigned(pipeline.getId(), pipeline.getName());
        }

        UUID assignerId = userId; // self-initiated (member opens the lesson)
        AssignmentService.AssignmentCreated created = assignmentService.createAssignmentForMember(
                currentUser.getOrganization(),
                pipeline,
                currentUser,
                assignerId,
                null,   // no deadline for LMS-embedded assignments
                1       // single check-in by default
        );

        log.info("Lazily created assignment {} and submission {} for user {} on pipeline {}",
                created.assignment().getId(), created.submission().getId(), userId, pipeline.getId());

        return AssessmentLinkDto.of(pipeline.getId(), pipeline.getName(),
                created.submission().getId(), created.submission().getStatus());
    }

    // -------------------------------------------------------------------------
    // GET — read-only resolve (+ auto-complete)
    // -------------------------------------------------------------------------

    /**
     * Returns the member's current submission status for the embedded pipeline,
     * or {@code assigned=false} if none exists.
     *
     * <p>If the submission is SUBMITTED or EVALUATED the lesson is automatically
     * marked complete on the enrollment.
     */
    @Transactional
    public AssessmentLinkDto resolveAssessment(String slug, UUID contentId) {
        Content content = resolveContent(slug, contentId);
        Pipeline pipeline = resolvePipeline(content);

        UUID userId = SecurityUtils.getCurrentUserId();
        List<Submission> existing =
                submissionRepository.findByUserIdAndPipelineIdOrderByCreatedAtDesc(userId, pipeline.getId());

        if (existing.isEmpty()) {
            return AssessmentLinkDto.notAssigned(pipeline.getId(), pipeline.getName());
        }

        Submission latest = existing.get(0);

        // Auto-complete lesson when assessment is SUBMITTED or EVALUATED
        if (latest.getStatus() == SubmissionStatus.SUBMITTED
                || latest.getStatus() == SubmissionStatus.EVALUATED) {
            UUID courseId = content.getSection().getCourse().getId();
            enrollmentRepository.findByUserIdAndCourseId(userId, courseId)
                    .ifPresent(enrollment ->
                            enrollmentService.markComplete(enrollment.getId(), contentId));
        }

        return AssessmentLinkDto.of(pipeline.getId(), pipeline.getName(),
                latest.getId(), latest.getStatus());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Content resolveContent(String slug, UUID contentId) {
        Content content = contentRepository.findByIdWithSectionAndCourse(contentId)
                .orElseThrow(() -> new ResourceNotFoundException("Content", contentId.toString()));

        // Verify slug → course ownership
        if (!content.getSection().getCourse().getSlug().equals(slug)) {
            throw new ResourceNotFoundException("Content", contentId.toString());
        }

        if (content.getContentType() != ContentType.ASSIGNMENT) {
            throw new BadRequestException(
                    "Content " + contentId + " is not an ASSIGNMENT lesson; "
                    + "embedded assessment is only available for ASSIGNMENT-type content.");
        }

        if (content.getPipelineId() == null) {
            throw new BadRequestException(
                    "ASSIGNMENT lesson " + contentId + " has no pipeline configured. "
                    + "An admin must link a pipeline before this lesson can be opened.");
        }

        return content;
    }

    private Pipeline resolvePipeline(Content content) {
        return pipelineRepository.findById(content.getPipelineId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Pipeline", content.getPipelineId().toString()));
    }
}
