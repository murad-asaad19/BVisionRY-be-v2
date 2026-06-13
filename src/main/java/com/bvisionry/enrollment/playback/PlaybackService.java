package com.bvisionry.enrollment.playback;

import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.enrollment.domain.ContentProgress;
import com.bvisionry.enrollment.domain.Enrollment;
import com.bvisionry.enrollment.repository.ContentProgressRepository;
import com.bvisionry.enrollment.repository.EnrollmentRepository;
import com.bvisionry.enrollment.web.EnrollmentService;

/**
 * Application service for video playback position tracking.
 *
 * <p>Saves the last known position and watched percentage for a content item
 * within an enrollment.  Auto-completes the content when {@code watchedPct >= 95}
 * by delegating to {@link EnrollmentService#markComplete}.
 */
@Service
public class PlaybackService {

    private final EnrollmentRepository enrollments;
    private final ContentProgressRepository progresses;
    private final EnrollmentService enrollmentService;

    public PlaybackService(EnrollmentRepository enrollments,
                           ContentProgressRepository progresses,
                           EnrollmentService enrollmentService) {
        this.enrollments = enrollments;
        this.progresses = progresses;
        this.enrollmentService = enrollmentService;
    }

    // -------------------------------------------------------------------------
    // Update position
    // -------------------------------------------------------------------------

    /**
     * Saves (or creates) the playback position for {@code contentId} within
     * {@code enrollmentId}.  Ownership is verified; returns the updated position
     * snapshot.
     */
    @Transactional
    public PositionDto updatePosition(UUID enrollmentId, UUID contentId,
                                      int positionSeconds, int durationSeconds) {
        Enrollment enrollment = requireOwnership(enrollmentId);

        ContentProgress cp = progresses
                .findByEnrollmentIdAndContentId(enrollmentId, contentId)
                .orElseGet(() -> {
                    ContentProgress fresh = new ContentProgress();
                    fresh.setEnrollment(enrollment);
                    fresh.setContentId(contentId);
                    return fresh;
                });

        int pct = Math.min(100, (int) Math.round((positionSeconds * 100.0) / durationSeconds));
        cp.setLastPositionSeconds(positionSeconds);
        cp.setWatchedPct(pct);
        progresses.save(cp);

        // Auto-complete when the learner has watched ≥95% and hasn't completed yet.
        if (pct >= 95 && !cp.isCompleted()) {
            enrollmentService.markComplete(enrollmentId, contentId);
            // Reload after markComplete (it may have updated the row via its own save).
            cp = progresses
                    .findByEnrollmentIdAndContentId(enrollmentId, contentId)
                    .orElse(cp);
        }

        return new PositionDto(cp.getLastPositionSeconds(), cp.getWatchedPct(), cp.isCompleted());
    }

    // -------------------------------------------------------------------------
    // Get position
    // -------------------------------------------------------------------------

    /**
     * Returns the saved playback position (or zero-state) for a content item.
     */
    @Transactional(readOnly = true)
    public PositionDto getPosition(UUID enrollmentId, UUID contentId) {
        requireOwnership(enrollmentId);

        return progresses
                .findByEnrollmentIdAndContentId(enrollmentId, contentId)
                .map(cp -> new PositionDto(cp.getLastPositionSeconds(), cp.getWatchedPct(), cp.isCompleted()))
                .orElse(new PositionDto(0, 0, false));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Enrollment requireOwnership(UUID enrollmentId) {
        Enrollment enrollment = enrollments.findById(enrollmentId)
                .orElseThrow(() -> new IllegalArgumentException("Enrollment not found: " + enrollmentId));

        UUID currentUserId = SecurityUtils.getCurrentUserId();
        if (!enrollment.getUserId().equals(currentUserId)) {
            throw new AccessDeniedException("Not your enrollment");
        }
        return enrollment;
    }
}
