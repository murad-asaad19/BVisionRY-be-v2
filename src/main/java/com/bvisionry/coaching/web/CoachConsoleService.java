package com.bvisionry.coaching.web;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.coaching.domain.CoachProfile;
import com.bvisionry.coaching.dto.CoachFounderDetailResponse;
import com.bvisionry.coaching.dto.CoachFounderDetailResponse.CoachExerciseSubmission;
import com.bvisionry.coaching.dto.CoachFounderDetailResponse.CoachModuleProgress;
import com.bvisionry.coaching.dto.CoachFounderDetailResponse.CoachPillarScore;
import com.bvisionry.coaching.dto.CoachFounderSummary;
import com.bvisionry.coaching.dto.CoachProfileResponse;
import com.bvisionry.coaching.dto.CoachReviewQueueResponse;
import com.bvisionry.coaching.dto.CoachRosterResponse;
import com.bvisionry.coaching.dto.UpdateCoachProfileRequest;
import com.bvisionry.coaching.repository.CoachProfileRepository;
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
    private final CoachProfileRepository profiles;
    private final CurrentUserAccessor currentUser;

    /* ------------------------------------------------------- the coach's own profile */

    /**
     * The caller's own profile. Absent row = no link published yet, which is a
     * legitimate state, not a 404.
     */
    @Transactional(readOnly = true)
    public CoachProfileResponse profile() {
        return profiles.findById(currentUser.require().userId())
                .map(p -> new CoachProfileResponse(p.getBookingUrl()))
                .orElseGet(() -> new CoachProfileResponse(null));
    }

    /**
     * Publish or withdraw the caller's Cal.com booking link.
     *
     * <p>The row's PK is the authenticated principal's id and nothing in the
     * request can name a different one — there is no path parameter and no id
     * field — so "a coach may only write their own row" is structural here
     * rather than checked. The URL itself is already validated by
     * {@code @CalComBookingUrl} on the request record (https + cal.com /
     * *.cal.com, dot-boundary), which is the authoritative check; the web form
     * mirrors it only for inline feedback.
     *
     * <p>Blank normalises to null so "cleared" has one representation in the
     * column and the founder-side card has one emptiness test.
     */
    @Transactional
    public CoachProfileResponse updateProfile(UpdateCoachProfileRequest request) {
        UUID coachId = currentUser.require().userId();
        CoachProfile profile = profiles.findById(coachId).orElseGet(() -> {
            CoachProfile fresh = new CoachProfile();
            fresh.setCoachId(coachId);
            return fresh;
        });
        String url = request.bookingUrl();
        profile.setBookingUrl(url == null || url.isBlank() ? null : url.trim());
        // saveAndFlush, not save: the founder-side read is raw SQL through the
        // same connection, so a write left sitting in the persistence context
        // would be invisible to it inside one transaction. Flushing here costs
        // nothing on a single-row upsert and removes the ordering hazard.
        return new CoachProfileResponse(profiles.saveAndFlush(profile).getBookingUrl());
    }

    @Transactional(readOnly = true)
    public CoachRosterResponse roster() {
        CurrentUser caller = currentUser.require();
        if (caller.orgId() == null) {
            return new CoachRosterResponse(List.of());
        }
        return new CoachRosterResponse(reads.roster(caller.orgId(), caller.userId()).stream()
                .map(CoachFounderSummary::from).toList());
    }

    /** The review queue: SUBMITTED exercises across the visible founders, oldest first. */
    @Transactional(readOnly = true)
    public CoachReviewQueueResponse queue() {
        CurrentUser caller = currentUser.require();
        if (caller.orgId() == null) {
            return new CoachReviewQueueResponse(List.of());
        }
        return new CoachReviewQueueResponse(
                reads.reviewQueue(caller.orgId(), caller.userId()).stream()
                        .map(CoachReviewQueueResponse.CoachQueueItem::from).toList());
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
