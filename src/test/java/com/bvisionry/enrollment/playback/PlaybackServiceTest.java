package com.bvisionry.enrollment.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.enrollment.domain.ContentProgress;
import com.bvisionry.enrollment.domain.Enrollment;
import com.bvisionry.enrollment.repository.ContentProgressRepository;
import com.bvisionry.enrollment.repository.EnrollmentRepository;
import com.bvisionry.enrollment.web.EnrollmentService;

/**
 * Unit tests for {@link PlaybackService}. Mirrors the Mockito style of
 * {@code EvaluationServiceTest} / {@code QuizServiceTest}: constructor injection via
 * {@link InjectMocks}, a real {@link User} principal installed in the
 * {@link SecurityContextHolder} so {@code SecurityUtils.getCurrentUserId()} resolves,
 * and {@link ArgumentCaptor} on the persisted {@link ContentProgress}.
 */
@ExtendWith(MockitoExtension.class)
class PlaybackServiceTest {

    @Mock private EnrollmentRepository enrollments;
    @Mock private ContentProgressRepository progresses;
    @Mock private EnrollmentService enrollmentService;

    @InjectMocks
    private PlaybackService service;

    private UUID currentUserId;
    private User currentUser;
    private UUID enrollmentId;
    private final UUID contentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        currentUserId = UUID.randomUUID();
        enrollmentId = UUID.randomUUID();

        currentUser = new User();
        currentUser.setId(currentUserId);
        currentUser.setEmail("learner@test.com");
        currentUser.setName("Learner");
        currentUser.setRole(UserRole.ORG_ADMIN);
        currentUser.setStatus(UserStatus.ACTIVE);

        var authorities = List.of(new SimpleGrantedAuthority(currentUser.getRole().name()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, null, authorities));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Enrollment ownedEnrollment() {
        Enrollment e = new Enrollment();
        e.setId(enrollmentId);
        e.setUserId(currentUserId);
        e.setCourseId(UUID.randomUUID());
        return e;
    }

    // =========================================================================
    // requireOwnership (exercised through getPosition / updatePosition)
    // =========================================================================

    @Test
    void getPosition_owner_returnsZeroStateWhenNoProgress() {
        when(enrollments.findById(enrollmentId)).thenReturn(Optional.of(ownedEnrollment()));
        when(progresses.findByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(Optional.empty());

        PositionDto dto = service.getPosition(enrollmentId, contentId);

        assertThat(dto.positionSeconds()).isZero();
        assertThat(dto.watchedPct()).isZero();
        assertThat(dto.completed()).isFalse();
    }

    @Test
    void getPosition_owner_returnsSavedPosition() {
        ContentProgress cp = new ContentProgress();
        cp.setContentId(contentId);
        cp.setLastPositionSeconds(42);
        cp.setWatchedPct(60);
        cp.setCompleted(false);

        when(enrollments.findById(enrollmentId)).thenReturn(Optional.of(ownedEnrollment()));
        when(progresses.findByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(Optional.of(cp));

        PositionDto dto = service.getPosition(enrollmentId, contentId);

        assertThat(dto.positionSeconds()).isEqualTo(42);
        assertThat(dto.watchedPct()).isEqualTo(60);
        assertThat(dto.completed()).isFalse();
    }

    @Test
    void getPosition_nonOwner_throwsAccessDenied() {
        Enrollment foreign = new Enrollment();
        foreign.setId(enrollmentId);
        foreign.setUserId(UUID.randomUUID()); // someone else
        foreign.setCourseId(UUID.randomUUID());
        when(enrollments.findById(enrollmentId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.getPosition(enrollmentId, contentId))
                .isInstanceOf(AccessDeniedException.class);

        verify(progresses, never()).findByEnrollmentIdAndContentId(any(), any());
    }

    @Test
    void getPosition_enrollmentMissing_throwsIllegalArgument() {
        when(enrollments.findById(enrollmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPosition(enrollmentId, contentId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updatePosition_nonOwner_throwsAccessDeniedBeforePersist() {
        Enrollment foreign = new Enrollment();
        foreign.setId(enrollmentId);
        foreign.setUserId(UUID.randomUUID());
        foreign.setCourseId(UUID.randomUUID());
        when(enrollments.findById(enrollmentId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.updatePosition(enrollmentId, contentId, 10, 100))
                .isInstanceOf(AccessDeniedException.class);

        verify(progresses, never()).save(any());
        verifyNoInteractions(enrollmentService);
    }

    // =========================================================================
    // updatePosition — persistence, watched-% math, auto-complete threshold
    // =========================================================================

    @Test
    void updatePosition_belowThreshold_savesProgressWithoutAutoComplete() {
        when(enrollments.findById(enrollmentId)).thenReturn(Optional.of(ownedEnrollment()));
        when(progresses.findByEnrollmentIdAndContentId(enrollmentId, contentId)).thenReturn(Optional.empty());
        when(progresses.save(any(ContentProgress.class))).thenAnswer(inv -> inv.getArgument(0));

        // 30 / 100 => 30% watched, well below the 95% auto-complete gate.
        PositionDto dto = service.updatePosition(enrollmentId, contentId, 30, 100);

        assertThat(dto.positionSeconds()).isEqualTo(30);
        assertThat(dto.watchedPct()).isEqualTo(30);
        assertThat(dto.completed()).isFalse();

        ArgumentCaptor<ContentProgress> captor = ArgumentCaptor.forClass(ContentProgress.class);
        verify(progresses).save(captor.capture());
        assertThat(captor.getValue().getContentId()).isEqualTo(contentId);
        assertThat(captor.getValue().getLastPositionSeconds()).isEqualTo(30);
        assertThat(captor.getValue().getWatchedPct()).isEqualTo(30);
        // Below 95% never delegates to markComplete.
        verifyNoInteractions(enrollmentService);
    }

    @Test
    void updatePosition_reachesThreshold_autoCompletesAndReloads() {
        Enrollment enr = ownedEnrollment();
        // After markComplete, a reload sees the row flipped to completed.
        ContentProgress completed = new ContentProgress();
        completed.setContentId(contentId);
        completed.setLastPositionSeconds(96);
        completed.setWatchedPct(96);
        completed.setCompleted(true);

        when(enrollments.findById(enrollmentId)).thenReturn(Optional.of(enr));
        when(progresses.findByEnrollmentIdAndContentId(enrollmentId, contentId))
                .thenReturn(Optional.empty())            // initial: create fresh row
                .thenReturn(Optional.of(completed));      // reload after markComplete
        when(progresses.save(any(ContentProgress.class))).thenAnswer(inv -> inv.getArgument(0));

        // 96 / 100 => 96% >= 95 auto-complete gate.
        PositionDto dto = service.updatePosition(enrollmentId, contentId, 96, 100);

        verify(enrollmentService).markComplete(enrollmentId, contentId);
        assertThat(dto.completed()).isTrue();
        assertThat(dto.watchedPct()).isEqualTo(96);
    }

    @Test
    void updatePosition_exactlyNinetyFivePercent_autoCompletes() {
        Enrollment enr = ownedEnrollment();
        ContentProgress completed = new ContentProgress();
        completed.setContentId(contentId);
        completed.setWatchedPct(95);
        completed.setCompleted(true);

        when(enrollments.findById(enrollmentId)).thenReturn(Optional.of(enr));
        when(progresses.findByEnrollmentIdAndContentId(enrollmentId, contentId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(completed));
        when(progresses.save(any(ContentProgress.class))).thenAnswer(inv -> inv.getArgument(0));

        // Boundary: exactly 95% must trigger auto-complete (gate is >=95).
        service.updatePosition(enrollmentId, contentId, 95, 100);

        verify(enrollmentService).markComplete(enrollmentId, contentId);
    }

    @Test
    void updatePosition_overruns_capsWatchedPctAtHundred() {
        Enrollment enr = ownedEnrollment();
        ContentProgress completed = new ContentProgress();
        completed.setContentId(contentId);
        completed.setLastPositionSeconds(200);
        completed.setWatchedPct(100);
        completed.setCompleted(true);

        when(enrollments.findById(enrollmentId)).thenReturn(Optional.of(enr));
        when(progresses.findByEnrollmentIdAndContentId(enrollmentId, contentId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(completed));
        when(progresses.save(any(ContentProgress.class))).thenAnswer(inv -> inv.getArgument(0));

        // position (200) exceeds duration (100): raw ratio 200% must clamp to 100.
        service.updatePosition(enrollmentId, contentId, 200, 100);

        ArgumentCaptor<ContentProgress> captor = ArgumentCaptor.forClass(ContentProgress.class);
        verify(progresses).save(captor.capture());
        assertThat(captor.getValue().getWatchedPct()).isEqualTo(100);
        assertThat(captor.getValue().getLastPositionSeconds()).isEqualTo(200);
        verify(enrollmentService).markComplete(enrollmentId, contentId);
    }

    @Test
    void updatePosition_alreadyCompleted_savesPositionButSkipsMarkComplete() {
        // An already-completed lesson still records the latest scrub position, but must
        // NOT re-trigger markComplete (which would re-run recompute/certificate work).
        Enrollment enr = ownedEnrollment();
        ContentProgress existing = new ContentProgress();
        existing.setContentId(contentId);
        existing.setCompleted(true);
        existing.setWatchedPct(100);
        existing.setLastPositionSeconds(50);

        when(enrollments.findById(enrollmentId)).thenReturn(Optional.of(enr));
        when(progresses.findByEnrollmentIdAndContentId(enrollmentId, contentId))
                .thenReturn(Optional.of(existing));
        when(progresses.save(any(ContentProgress.class))).thenAnswer(inv -> inv.getArgument(0));

        PositionDto dto = service.updatePosition(enrollmentId, contentId, 97, 100);

        assertThat(dto.positionSeconds()).isEqualTo(97);
        assertThat(dto.completed()).isTrue();
        // Guarded by !cp.isCompleted(): no second completion pass.
        verify(enrollmentService, never()).markComplete(any(), any());
        // Only the initial find; no post-markComplete reload.
        verify(progresses).findByEnrollmentIdAndContentId(eq(enrollmentId), eq(contentId));
    }
}
