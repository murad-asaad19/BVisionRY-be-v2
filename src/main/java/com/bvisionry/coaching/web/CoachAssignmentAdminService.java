package com.bvisionry.coaching.web;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.coaching.domain.CoachAssignment;
import com.bvisionry.coaching.dto.CoachAssignmentResponse;
import com.bvisionry.coaching.dto.CreateCoachAssignmentRequest;
import com.bvisionry.coaching.repository.CoachAssignmentRepository;
import com.bvisionry.coaching.repository.CoachingReadRepository;
import com.bvisionry.coaching.repository.CoachingReadRepository.OrgUserRow;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.security.CurrentUserAccessor;

import com.bvisionry.common.programaccess.OrgCohortAccess;
import lombok.RequiredArgsConstructor;

/**
 * Org-admin management of coach grants. Every referenced row (coach, cohort,
 * founder, the grant itself) is resolved WITH the org predicate, so a foreign
 * id — even a real one — reads as absent (404), never as reachable.
 */
@Service
@RequiredArgsConstructor
public class CoachAssignmentAdminService {

    private final CoachAssignmentRepository assignments;
    private final CoachingReadRepository reads;
    private final OrgCohortAccess orgCohorts;
    private final CurrentUserAccessor currentUser;

    @Transactional(readOnly = true)
    public List<CoachAssignmentResponse> list(UUID orgId) {
        List<CoachAssignment> rows = assignments.findByOrgIdOrderByCreatedAtAsc(orgId);
        Set<UUID> userIds = rows.stream()
                .flatMap(a -> java.util.stream.Stream.of(a.getCoachId(), a.getMemberId()))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> cohortIds = rows.stream().map(CoachAssignment::getCohortId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, OrgUserRow> users = reads.usersInOrg(orgId, userIds);
        Map<UUID, String> cohorts = reads.cohortNamesInOrg(orgId, cohortIds);
        return rows.stream().map(a -> toResponse(a, users, cohorts)).toList();
    }

    @Transactional
    public CoachAssignmentResponse create(UUID orgId, CreateCoachAssignmentRequest request) {
        // Three grains (V176), so only BOTH is rejected: neither IS the grain
        // "every member of this org".
        if (request.cohortId() != null && request.memberId() != null) {
            throw new BadRequestException(
                    "Assign the coach to a cohort or a founder, not both.");
        }

        OrgUserRow coach = reads.userInOrg(orgId, request.coachId())
                .orElseThrow(() -> new ResourceNotFoundException("Coach",
                        request.coachId().toString()));
        if (!"COACH".equals(coach.role())) {
            throw new BadRequestException("Only a user with the Coach role can be assigned.");
        }

        String cohortName = null;
        OrgUserRow member = null;
        if (request.cohortId() != null) {
            cohortName = orgCohorts.cohortNameInOrg(orgId, request.cohortId())
                    .orElseThrow(() -> new ResourceNotFoundException("Cohort",
                            request.cohortId().toString()));
            if (assignments.existsByOrgIdAndCoachIdAndCohortId(orgId, coach.id(),
                    request.cohortId())) {
                throw new BadRequestException("This coach is already assigned to that cohort.");
            }
        } else if (request.memberId() != null) {
            member = reads.userInOrg(orgId, request.memberId())
                    .orElseThrow(() -> new ResourceNotFoundException("Member",
                            request.memberId().toString()));
            if (!"MEMBER".equals(member.role())) {
                throw new BadRequestException("A coach can only be assigned to a member.");
            }
            if (assignments.existsByOrgIdAndCoachIdAndMemberId(orgId, coach.id(),
                    request.memberId())) {
                throw new BadRequestException("This coach is already assigned to that founder.");
            }
        } else if (assignments.existsByOrgIdAndCoachIdAndCohortIdIsNullAndMemberIdIsNull(
                orgId, coach.id())) {
            // Org-wide grain: nothing to look up — the org itself is the target,
            // and it is already resolved by the guard stack.
            throw new BadRequestException("This coach already covers the whole organization.");
        }

        CoachAssignment assignment = new CoachAssignment();
        assignment.setOrgId(orgId);
        assignment.setCoachId(coach.id());
        assignment.setCohortId(request.cohortId());
        assignment.setMemberId(request.memberId());
        assignment.setAssignedBy(currentUser.require().userId());
        CoachAssignment saved = assignments.save(assignment);

        return new CoachAssignmentResponse(saved.getId(), coach.id(), coach.name(),
                coach.email(), saved.getCohortId(), cohortName, saved.getMemberId(),
                member == null ? null : member.name(), member == null ? null : member.email(),
                saved.getCohortId() == null && saved.getMemberId() == null,
                saved.getCreatedAt());
    }

    @Transactional
    public void delete(UUID orgId, UUID assignmentId) {
        CoachAssignment assignment = assignments.findByIdAndOrgId(assignmentId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Coach assignment",
                        assignmentId.toString()));
        assignments.delete(assignment);
    }

    private CoachAssignmentResponse toResponse(CoachAssignment a, Map<UUID, OrgUserRow> users,
                                               Map<UUID, String> cohorts) {
        OrgUserRow coach = users.get(a.getCoachId());
        OrgUserRow member = a.getMemberId() == null ? null : users.get(a.getMemberId());
        return new CoachAssignmentResponse(a.getId(), a.getCoachId(),
                coach == null ? null : coach.name(), coach == null ? null : coach.email(),
                a.getCohortId(), a.getCohortId() == null ? null : cohorts.get(a.getCohortId()),
                a.getMemberId(), member == null ? null : member.name(),
                member == null ? null : member.email(),
                a.getCohortId() == null && a.getMemberId() == null, a.getCreatedAt());
    }
}
