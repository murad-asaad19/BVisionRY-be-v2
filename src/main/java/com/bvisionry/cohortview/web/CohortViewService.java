package com.bvisionry.cohortview.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.cohortview.dto.CohortOutlineResponse;
import com.bvisionry.cohortview.dto.CohortOutlineResponse.OutlineModule;
import com.bvisionry.cohortview.dto.CohortOutlineResponse.OutlineTask;
import com.bvisionry.cohortview.dto.CohortOverviewResponse;
import com.bvisionry.cohortview.dto.CohortOverviewResponse.CohortActivityItem;
import com.bvisionry.cohortview.dto.CohortOverviewResponse.CohortCoach;
import com.bvisionry.cohortview.dto.CohortOverviewResponse.Milestone;
import com.bvisionry.cohortview.dto.CohortRosterResponse;
import com.bvisionry.cohortview.dto.CohortRosterResponse.CohortRosterMember;
import com.bvisionry.cohortview.dto.CourseProgressDetailResponse;
import com.bvisionry.cohortview.dto.CourseProgressDetailResponse.CourseLesson;
import com.bvisionry.cohortview.dto.CourseProgressDetailResponse.CourseSection;
import com.bvisionry.cohortview.repository.CohortViewReadRepository;
import com.bvisionry.cohortview.repository.CohortViewReadRepository.CohortRow;
import com.bvisionry.cohortview.repository.CohortViewReadRepository.CourseRow;
import com.bvisionry.cohortview.repository.CohortViewReadRepository.LessonRow;
import com.bvisionry.common.exception.ResourceNotFoundException;

/**
 * Assembles the dedicated cohort view (redesign spec §13.7). The controller
 * passes the org guard stack; this service re-anchors every read on the
 * org-scoped row — a cohort not assigned to the org, or a member not in it, is
 * a 404 here regardless of what the guard let through.
 */
@Service
@Transactional(readOnly = true)
public class CohortViewService {

    private final CohortViewReadRepository reads;

    public CohortViewService(CohortViewReadRepository reads) {
        this.reads = reads;
    }

    public CohortOverviewResponse overview(UUID orgId, UUID cohortId) {
        CohortRow cohort = reads.cohort(orgId, cohortId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));

        return new CohortOverviewResponse(cohort.id(), cohort.name(), cohort.description(),
                cohort.status(), cohort.createdAt(), cohort.startAt(), cohort.endAt(),
                // No per-cohort seat count exists (V167 meters launches per org).
                null,
                cohort.baselinePipelineName(), cohort.distancePipelineName(),
                reads.coaches(orgId, cohortId).stream()
                        .map(c -> new CohortCoach(c.id(), c.name(), c.orgWide()))
                        .toList(),
                reads.milestones(orgId, cohortId).stream()
                        .map(m -> new Milestone(m.taskId(), m.name(), m.role(), m.dueDate(),
                                m.doneCount(), m.totalMembers()))
                        .toList(),
                reads.activity(orgId, cohortId).stream()
                        .map(a -> new CohortActivityItem(a.type(), a.memberName(), a.title(), a.at()))
                        .toList());
    }

    /**
     * The cohort's curriculum, read-only: modules in board order with their LIVE
     * tasks and the org slice's progress on each. Tasks are grouped in one pass
     * off the module-ordered read, so the outline needs two queries whatever the
     * curriculum's size.
     */
    public CohortOutlineResponse outline(UUID orgId, UUID cohortId) {
        CohortRow cohort = reads.cohort(orgId, cohortId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));

        Map<UUID, List<OutlineTask>> byModule = new LinkedHashMap<>();
        int taskCount = 0;
        for (var t : reads.outlineTasks(orgId, cohortId)) {
            byModule.computeIfAbsent(t.moduleId(), k -> new ArrayList<>())
                    .add(new OutlineTask(t.taskId(), t.name(), t.taskType(), t.milestoneRole(),
                            t.dueDate(), t.position(), t.doneCount(), t.totalMembers()));
            taskCount++;
        }

        List<OutlineModule> modules = reads.outlineModules(orgId, cohortId).stream()
                .map(m -> new OutlineModule(m.moduleId(), m.name(), m.summary(), m.pillarLabel(),
                        m.position(), m.lockMode(), m.unlockAt(), m.audienceMode(),
                        m.audienceCount(),
                        byModule.getOrDefault(m.moduleId(), List.of())))
                .toList();

        return new CohortOutlineResponse(cohort.id(), cohort.name(), cohort.status(),
                taskCount, modules);
    }

    public CohortRosterResponse roster(UUID orgId, UUID cohortId) {
        reads.cohort(orgId, cohortId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));

        return new CohortRosterResponse(reads.roster(orgId, cohortId).stream()
                .map(r -> new CohortRosterMember(r.userId(), r.name(), r.email(), r.memberType(),
                        r.friLatest(), r.friDelta(), r.progressDone(), r.progressTotal(),
                        r.overdueCount(), r.awaitingReview(), r.lastActivityAt()))
                .toList());
    }

    /**
     * One member's course progress. Tenancy is re-anchored on the member (the
     * founder-profile stance), then on the course: an org that cannot see the
     * course gets a 404 unless the member is already enrolled in it.
     */
    public CourseProgressDetailResponse courseProgress(UUID orgId, UUID memberId, UUID courseId) {
        if (!reads.isOrgMember(orgId, memberId)) {
            throw new ResourceNotFoundException("Member", memberId.toString());
        }
        CourseRow course = reads.course(orgId, memberId, courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", courseId.toString()));

        return new CourseProgressDetailResponse(course.courseId(), course.title(), course.status(),
                course.progressPct(), course.enrolledAt(), course.completedAt(),
                course.certificateIssued(), sections(reads.lessons(memberId, courseId)));
    }

    /**
     * Folds the flat section × lesson read into the nested shape. The rows
     * arrive in authoring order, so a section break is simply "the id changed";
     * a lesson-less section contributes its one null-content row as an empty
     * list.
     */
    private static List<CourseSection> sections(List<LessonRow> rows) {
        List<CourseSection> sections = new ArrayList<>();
        UUID currentId = null;
        List<CourseLesson> lessons = null;
        for (LessonRow row : rows) {
            if (!row.sectionId().equals(currentId)) {
                currentId = row.sectionId();
                lessons = new ArrayList<>();
                sections.add(new CourseSection(row.sectionId(), row.sectionTitle(),
                        row.sectionPosition(), lessons));
            }
            if (row.contentId() != null) {
                lessons.add(new CourseLesson(row.contentId(), row.contentTitle(),
                        row.contentPosition(), row.completed(), row.completedAt()));
            }
        }
        return sections;
    }
}
