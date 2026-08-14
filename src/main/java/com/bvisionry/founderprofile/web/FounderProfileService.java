package com.bvisionry.founderprofile.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.founderprofile.dto.FounderProfileResponse;
import com.bvisionry.founderprofile.dto.FounderProfileResponse.FounderAnnouncement;
import com.bvisionry.founderprofile.dto.FounderProfileResponse.FounderCoachRef;
import com.bvisionry.founderprofile.dto.FounderProfileResponse.FounderPillarScore;
import com.bvisionry.founderprofile.dto.FounderProfileResponse.FounderProfileHeader;
import com.bvisionry.founderprofile.dto.FounderProfileResponse.FounderProfileNote;
import com.bvisionry.founderprofile.dto.FounderProfileResponse.FounderWorkItem;
import com.bvisionry.founderprofile.repository.FounderProfileReadRepository;
import com.bvisionry.founderprofile.repository.FounderProfileReadRepository.AssessmentRow;
import com.bvisionry.founderprofile.repository.FounderProfileReadRepository.CourseRow;
import com.bvisionry.founderprofile.repository.FounderProfileReadRepository.ExerciseRow;
import com.bvisionry.founderprofile.repository.FounderProfileReadRepository.FriPoint;
import com.bvisionry.founderprofile.repository.FounderProfileReadRepository.MemberRow;
import com.bvisionry.founderprofile.repository.FounderProfileReadRepository.ProgramTaskRow;

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
                coaches(orgId, memberId),
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

    /** LESSON Work-tab status: the submission's own, or SUBMITTED once done. */
    static String lessonStatus(ProgramTaskRow t) {
        return t.done() ? "SUBMITTED" : t.status();
    }

    /**
     * Coach grants grouped per coach: cohort grants, a direct flag and an
     * org-wide flag. Since V176 the grain is read off BOTH ids — a null
     * {@code cohortId} used to imply "direct", and with the org-wide grant in
     * the union that shortcut would report the house coach as a direct grant.
     */
    private List<FounderCoachRef> coaches(UUID orgId, UUID memberId) {
        Map<UUID, List<FounderProfileReadRepository.CoachRow>> byCoach = reads
                .coaches(orgId, memberId).stream()
                .collect(Collectors.groupingBy(FounderProfileReadRepository.CoachRow::coachId,
                        LinkedHashMap::new, Collectors.toList()));
        return byCoach.values().stream()
                .map(grants -> new FounderCoachRef(
                        grants.get(0).coachId(), grants.get(0).coachName(),
                        grants.stream().map(FounderProfileReadRepository.CoachRow::cohortId)
                                .filter(java.util.Objects::nonNull).distinct().toList(),
                        grants.stream().anyMatch(g -> g.memberId() != null),
                        grants.stream()
                                .anyMatch(g -> g.cohortId() == null && g.memberId() == null)))
                .toList();
    }

    /**
     * The unified Work list, cohort-attributed (spec §2.4 as amended
     * 2026-08-12): each cohort task row is MERGED with the artifact its
     * {@code program_task_id} tag points at (V164/V173), so the row carries
     * the real type, the fine-grained status, the score and the link target —
     * and the artifact is suppressed from the org-level remainder. Whatever
     * carries no tag (direct assignments, self-enrolled courses, untagged
     * submissions) stays as its own row with a null {@code cohortId}: the
     * Direct bucket.
     */
    private List<FounderWorkItem> workItems(UUID orgId, UUID memberId) {
        List<ProgramTaskRow> tasks = reads.programTasks(orgId, memberId);
        List<ExerciseRow> exercises = reads.exercises(orgId, memberId);
        List<CourseRow> courses = reads.courses(orgId, memberId);
        List<AssessmentRow> assessments = reads.assessments(orgId, memberId);

        Map<UUID, ExerciseRow> exerciseByTask = exercises.stream()
                .filter(e -> e.programTaskId() != null)
                .collect(Collectors.toMap(ExerciseRow::programTaskId, Function.identity(),
                        (a, b) -> a));
        Map<UUID, AssessmentRow> assessmentByTask = assessments.stream()
                .filter(a -> a.programTaskId() != null)
                .collect(Collectors.toMap(AssessmentRow::programTaskId, Function.identity(),
                        (a, b) -> a));
        // Courses key on the course id — one global enrollment per (member,
        // course), shared by every task that references it (V173's deliberate
        // COURSE exception).
        Map<UUID, CourseRow> courseById = new HashMap<>();
        courses.forEach(c -> courseById.putIfAbsent(c.courseId(), c));

        Set<UUID> mergedExercises = new HashSet<>();
        Set<UUID> mergedSubmissions = new HashSet<>();
        Set<UUID> mergedCourses = new HashSet<>();
        // Pipelines an ASSESSMENT task of the member's already represents: a
        // bare never-started assignment for one would double-show ("To do" on
        // the task AND in Direct) — same representation rule as the journey's
        // direct list (review decision #6), applied to the not-yet-tagged case.
        Set<UUID> taskPipelines = tasks.stream()
                .filter(t -> "ASSESSMENT".equals(t.taskType()) && t.refId() != null)
                .map(ProgramTaskRow::refId)
                .collect(Collectors.toSet());

        List<FounderWorkItem> items = new ArrayList<>();
        for (ProgramTaskRow t : tasks) {
            String context = t.cohortName() + " · " + t.moduleName();
            Instant taskDue = t.dueDate() == null
                    ? null : t.dueDate().atStartOfDay(ZoneOffset.UTC).toInstant();
            items.add(switch (t.taskType()) {
                case "EXERCISE" -> {
                    ExerciseRow e = exerciseByTask.get(t.taskId());
                    if (e != null) {
                        mergedExercises.add(e.assignmentId());
                    }
                    yield new FounderWorkItem("PROGRAM", t.taskId(), t.cohortId(), "EXERCISE",
                            e == null ? null : e.assignmentId(), null, t.taskName(), context,
                            e == null ? null : e.status(), null, null, null,
                            e != null && e.deadline() != null ? e.deadline() : taskDue,
                            e == null ? null : e.lastSavedAt(),
                            e == null ? null : e.submittedAt(),
                            e == null ? null : e.reviewedAt(), null, null, false, false,
                            e == null ? null : e.qualityTagLabel(),
                            e == null ? null : e.qualityTaggedAt(),
                            e == null ? null : e.assignedAt());
                }
                case "ASSESSMENT" -> {
                    AssessmentRow a = assessmentByTask.get(t.taskId());
                    if (a != null && a.submissionId() != null) {
                        mergedSubmissions.add(a.submissionId());
                    }
                    yield new FounderWorkItem("PROGRAM", t.taskId(), t.cohortId(), "ASSESSMENT",
                            null, a == null ? null : a.submissionId(), t.taskName(), context,
                            a == null ? null : a.status(), null, null,
                            a == null ? null : a.score(),
                            a != null && a.deadline() != null ? a.deadline() : taskDue,
                            a == null ? null : a.startedAt(),
                            a == null ? null : a.submittedAt(), null,
                            a == null ? null : a.evaluatedAt(), null, false, false, null, null,
                            a == null ? null : a.assignedAt());
                }
                case "COURSE" -> {
                    CourseRow c = t.refId() == null ? null : courseById.get(t.refId());
                    if (c != null) {
                        mergedCourses.add(c.courseId());
                    }
                    yield new FounderWorkItem("PROGRAM", t.taskId(), t.cohortId(), "COURSE",
                            t.refId(), null, t.taskName(), context,
                            c == null ? null : c.status(), null,
                            c == null || c.status() == null ? null : c.progressPct(), null,
                            taskDue, c == null ? null : c.enrolledAt(), null, null, null,
                            c == null ? null : c.completedAt(), false, false, null, null,
                            c == null ? null : c.enrolledAt());
                }
                case "SURVEY" -> new FounderWorkItem("PROGRAM", t.taskId(), t.cohortId(),
                        "SURVEY", t.surveyResponseId(), null, t.taskName(), context,
                        t.surveyResponseId() == null ? null : "SUBMITTED", null, null, null,
                        taskDue, null, t.surveySubmittedAt(), null, null, null, false, false,
                        null, null, null);
                // LESSON — the in-place form task.
                default -> new FounderWorkItem("PROGRAM", t.taskId(), t.cohortId(), "LESSON",
                        null, null, t.taskName(), context, lessonStatus(t), null, null, null,
                        taskDue, t.savedAt(), t.submittedAt(), null, null, null, false, false,
                        null, null, null);
            });
        }

        // ---- the untagged remainder: direct/org-level work (cohortId null) ----
        exercises.stream()
                .filter(e -> !mergedExercises.contains(e.assignmentId()))
                .forEach(e -> items.add(new FounderWorkItem(
                        "EXERCISE", e.assignmentId(), null, null, null, null, e.exerciseName(),
                        null, e.status(),
                        null, null, null, e.deadline(), e.lastSavedAt(), e.submittedAt(),
                        e.reviewedAt(), null, null, false, false, e.qualityTagLabel(),
                        e.qualityTaggedAt(), e.assignedAt())));
        // Spec §3: the source is a stored column now, not a guess off the pillar
        // sub-select. A null status means an org rule covers the member with no
        // enrollment row yet — the Work tab shows it as assigned. A removed row
        // survives the merge suppression on purpose: it IS the audit trail.
        courses.stream()
                .filter(c -> c.removed() || !mergedCourses.contains(c.courseId()))
                .forEach(c -> items.add(new FounderWorkItem(
                        "COURSE", c.courseId(), null, null, null, null, c.title(), c.pillarName(),
                        c.status() == null ? "ASSIGNED" : c.status(),
                        c.source() == null ? "SELF" : c.source(), c.progressPct(), null,
                        c.deadline(),
                        c.enrolledAt(), null, null, null, c.completedAt(), c.removed(),
                        c.required(), null, null, c.enrolledAt())));
        assessments.stream()
                .filter(a -> a.submissionId() == null
                        ? !taskPipelines.contains(a.pipelineId())
                        : !mergedSubmissions.contains(a.submissionId()))
                .forEach(a -> items.add(new FounderWorkItem(
                        "ASSESSMENT", a.assignmentId(), null, null, null, a.submissionId(),
                        a.pipelineName(), null,
                        a.status(), null, null, a.score(), a.deadline(), a.startedAt(),
                        a.submittedAt(), null, a.evaluatedAt(), null, false, false, null,
                        null, a.assignedAt())));
        return items;
    }
}
