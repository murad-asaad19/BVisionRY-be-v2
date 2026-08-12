package com.bvisionry.founderprofile.web;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.founderprofile.dto.FounderProfileResponse;
import com.bvisionry.founderprofile.dto.FounderProfileResponse.FounderAnnouncement;
import com.bvisionry.founderprofile.dto.FounderProfileResponse.FounderPillarScore;
import com.bvisionry.founderprofile.dto.FounderProfileResponse.FounderProfileHeader;
import com.bvisionry.founderprofile.dto.FounderProfileResponse.FounderProfileNote;
import com.bvisionry.founderprofile.dto.FounderProfileResponse.FounderWorkItem;
import com.bvisionry.founderprofile.repository.FounderProfileReadRepository;
import com.bvisionry.founderprofile.repository.FounderProfileReadRepository.FriPoint;
import com.bvisionry.founderprofile.repository.FounderProfileReadRepository.MemberRow;

/**
 * Assembles the shared founder profile. CALLERS AUTHORIZE FIRST: the admin
 * controller passes the org guard stack, the coach controller passes the
 * {@link com.bvisionry.common.coachaccess.CoachAccess} gate; this service then
 * re-anchors every read on the org-scoped {@code member} row (a foreign or
 * non-member id is a 404 here regardless).
 */
@Service
@Transactional(readOnly = true)
public class FounderProfileService {

    private final FounderProfileReadRepository reads;

    public FounderProfileService(FounderProfileReadRepository reads) {
        this.reads = reads;
    }

    public FounderProfileResponse profile(UUID orgId, UUID memberId) {
        MemberRow member = reads.member(orgId, memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", memberId.toString()));

        List<FriPoint> fri = reads.friTrajectory(memberId);
        FriPoint latest = fri.isEmpty() ? null : fri.get(fri.size() - 1);

        FounderProfileHeader header = new FounderProfileHeader(
                member.id(), member.name(), member.email(), member.role(), member.status(),
                member.userType(),
                reads.cohorts(orgId, memberId).stream()
                        .map(c -> new FounderProfileResponse.FounderCohortRef(c.id(), c.name()))
                        .toList(),
                latest == null ? null : latest.score(),
                friDelta(fri),
                latest == null ? null : latest.evaluatedAt(),
                member.lastActivityAt(), member.lastLoginAt());

        return new FounderProfileResponse(header, workItems(orgId, memberId),
                reads.pillarScores(memberId).stream()
                        .map(p -> new FounderPillarScore(p.pillarName(), p.scorePercentage(),
                                p.maturityLabel(), p.evaluatedAt()))
                        .toList(),
                reads.notes(orgId, memberId).stream()
                        .map(n -> new FounderProfileNote(n.id(), n.coachId(), n.coachName(),
                                n.body(), n.createdAt(), n.updatedAt()))
                        .toList(),
                reads.announcements(orgId, memberId).stream()
                        .map(a -> new FounderAnnouncement(a.id(), a.cohortName(), a.authorName(),
                                a.body(), a.createdAt()))
                        .toList());
    }

    /** Δ-so-far: latest minus earliest evaluated overall; null with fewer than two points. */
    static BigDecimal friDelta(List<FriPoint> trajectory) {
        if (trajectory.size() < 2) {
            return null;
        }
        BigDecimal first = trajectory.get(0).score();
        BigDecimal last = trajectory.get(trajectory.size() - 1).score();
        if (first == null || last == null) {
            return null;
        }
        return last.subtract(first);
    }

    /**
     * A cohort task's Work-tab status. Only LESSON tasks own a
     * {@code program_submissions} row, so reading that column alone reported
     * every other type as "To do" forever; the DONE verdict comes from the
     * shared authority ({@code TaskCompletion.DONE_FOR_USER}) instead, worded
     * per type in this endpoint's existing vocabulary.
     *
     * <p>ponytail: done/not-done only — the authority is a boolean. An
     * in-flight course or a changes-requested exercise still reads "To do"
     * here; its own typed row on the same tab carries the finer state.
     */
    static String programStatus(FounderProfileReadRepository.ProgramTaskRow t) {
        if (!t.done()) {
            return t.status();
        }
        return switch (t.taskType()) {
            case "COURSE" -> "COMPLETED";
            default -> "SUBMITTED";
        };
    }

    private List<FounderWorkItem> workItems(UUID orgId, UUID memberId) {
        List<FounderWorkItem> items = new ArrayList<>();
        reads.programTasks(orgId, memberId).forEach(t -> items.add(new FounderWorkItem(
                "PROGRAM", t.taskId(), null, t.taskName(),
                t.cohortName() + " · " + t.moduleName(), programStatus(t), null, null, null,
                t.dueDate() == null ? null : t.dueDate().atStartOfDay(ZoneOffset.UTC).toInstant(),
                t.savedAt(), t.submittedAt(), null, null, null, false, false, null, null, null)));
        reads.exercises(orgId, memberId).forEach(e -> items.add(new FounderWorkItem(
                "EXERCISE", e.assignmentId(), null, e.exerciseName(), null, e.status(),
                null, null, null, e.deadline(), e.lastSavedAt(), e.submittedAt(),
                e.reviewedAt(), null, null, false, false, e.qualityTagLabel(),
                e.qualityTaggedAt(), e.assignedAt())));
        // Spec §3: the source is a stored column now, not a guess off the pillar
        // sub-select. A null status means an org rule covers the member with no
        // enrollment row yet — the Work tab shows it as assigned.
        reads.courses(orgId, memberId).forEach(c -> items.add(new FounderWorkItem(
                "COURSE", c.courseId(), null, c.title(), c.pillarName(),
                c.status() == null ? "ASSIGNED" : c.status(),
                c.source() == null ? "SELF" : c.source(), c.progressPct(), null, c.deadline(),
                c.enrolledAt(), null, null, null, c.completedAt(), c.removed(), c.required(),
                null, null, c.enrolledAt())));
        reads.assessments(orgId, memberId).forEach(a -> items.add(new FounderWorkItem(
                "ASSESSMENT", a.assignmentId(), a.submissionId(), a.pipelineName(), null,
                a.status(), null, null, a.score(), a.deadline(), a.startedAt(),
                a.submittedAt(), null, a.evaluatedAt(), null, false, false, null,
                null, a.assignedAt())));
        return items;
    }
}
