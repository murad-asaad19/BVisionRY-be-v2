package com.bvisionry.coaching.web;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.coaching.dto.CoachFounderDetailResponse;
import com.bvisionry.coaching.dto.CoachFounderDetailResponse.CoachExerciseSubmission;
import com.bvisionry.coaching.dto.CoachFounderDetailResponse.CoachModuleProgress;
import com.bvisionry.coaching.dto.CoachFounderDetailResponse.CoachPillarScore;
import com.bvisionry.coaching.dto.CoachFounderSummary;
import com.bvisionry.coaching.dto.CoachRosterResponse;
import com.bvisionry.coaching.repository.CoachingReadRepository;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;

import lombok.RequiredArgsConstructor;

/**
 * The coach's own console: roster + founder drill-in. The caller's identity is
 * the scope — every read carries (orgId, coachId) into SQL that embeds the
 * assignment-union predicate, so a founder outside the union is a 404 here even
 * if the HTTP and method layers were misconfigured.
 */
@Service
@RequiredArgsConstructor
public class CoachConsoleService {

    private final CoachingReadRepository reads;
    private final CurrentUserAccessor currentUser;

    @Transactional(readOnly = true)
    public CoachRosterResponse roster() {
        CurrentUser caller = currentUser.require();
        if (caller.orgId() == null) {
            return new CoachRosterResponse(List.of());
        }
        return new CoachRosterResponse(reads.roster(caller.orgId(), caller.userId()).stream()
                .map(CoachFounderSummary::from).toList());
    }

    @Transactional(readOnly = true)
    public CoachFounderDetailResponse founderDetail(UUID founderId) {
        CurrentUser caller = currentUser.require();
        UUID orgId = caller.orgId();
        UUID coachId = caller.userId();
        CoachFounderSummary founder = (orgId == null
                ? java.util.Optional.<CoachingReadRepository.RosterRow>empty()
                : reads.visibleFounder(orgId, coachId, founderId))
                .map(CoachFounderSummary::from)
                .orElseThrow(() -> new ResourceNotFoundException("Founder", founderId.toString()));

        return new CoachFounderDetailResponse(
                founder,
                reads.pillarScores(orgId, coachId, founderId).stream()
                        .map(r -> new CoachPillarScore(r.pillarName(), r.scorePercentage(),
                                r.maturityLabel(), r.evaluatedAt()))
                        .toList(),
                reads.moduleProgress(orgId, coachId, founderId).stream()
                        .map(r -> new CoachModuleProgress(r.cohortName(), r.moduleName(),
                                r.totalTasks(), r.submittedTasks()))
                        .toList(),
                reads.exerciseSubmissions(orgId, coachId, founderId).stream()
                        .map(r -> new CoachExerciseSubmission(r.assignmentId(), r.exerciseName(),
                                r.status(), r.submittedAt()))
                        .toList());
    }
}
