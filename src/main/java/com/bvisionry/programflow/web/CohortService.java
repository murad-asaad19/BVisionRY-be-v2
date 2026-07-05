package com.bvisionry.programflow.web;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.programflow.domain.Cohort;
import com.bvisionry.programflow.dto.CohortDto;
import com.bvisionry.programflow.dto.CreateCohortRequest;
import com.bvisionry.programflow.dto.ProgramOrgDto;
import com.bvisionry.programflow.dto.UpdateCohortMembersRequest;
import com.bvisionry.programflow.dto.UpdateCohortRequest;
import com.bvisionry.programflow.repository.CohortRepository;
import com.bvisionry.programflow.repository.OrgMemberRow;
import com.bvisionry.programflow.repository.TeamRepository;

import lombok.RequiredArgsConstructor;

/** Admin cohort management: list, create, rename/finish, delete, enrolment. */
@Service
@RequiredArgsConstructor
@Transactional
public class CohortService {

    private final CohortRepository cohorts;
    private final TeamRepository teams;

    @Transactional(readOnly = true)
    public List<CohortDto> list(UUID orgId) {
        return cohorts.findByOrgIdOrderByPositionAsc(orgId).stream().map(CohortDto::of).toList();
    }

    /** Every org with learner + cohort counts (switcher shows cohorted orgs, picker the rest). */
    @Transactional(readOnly = true)
    public List<ProgramOrgDto> listOrgs() {
        return cohorts.findOrgProgramRows().stream()
                .map(r -> new ProgramOrgDto(r.getId(), r.getName(), r.getDescription(),
                        (int) r.getMemberCount(), (int) r.getCohortCount()))
                .toList();
    }

    public CohortDto create(UUID orgId, CreateCohortRequest req) {
        Cohort c = new Cohort();
        c.setOrgId(orgId);
        c.setName(req.name());
        c.setPosition(cohorts.findByOrgIdOrderByPositionAsc(orgId).size());
        if (req.enrollAllMembers()) {
            teams.findOrgMembers(orgId).forEach(m -> c.getMemberIds().add(m.getId()));
        }
        return CohortDto.of(cohorts.save(c));
    }

    public CohortDto update(UUID orgId, UUID cohortId, UpdateCohortRequest req) {
        Cohort c = require(orgId, cohortId);
        c.setName(req.name());
        c.setStatus(req.status());
        return CohortDto.of(c);
    }

    public void delete(UUID orgId, UUID cohortId) {
        cohorts.delete(require(orgId, cohortId));
    }

    /** Replaces the enrolled learner set, validating every id is an org member. */
    public CohortDto setMembers(UUID orgId, UUID cohortId, UpdateCohortMembersRequest req) {
        Cohort c = require(orgId, cohortId);
        Set<UUID> orgMemberIds = teams.findOrgMembers(orgId).stream()
                .map(OrgMemberRow::getId).collect(Collectors.toSet());
        if (!orgMemberIds.containsAll(req.memberIds())) {
            throw new BadRequestException("One or more learners do not belong to this organization");
        }
        c.setMemberIds(new LinkedHashSet<>(req.memberIds()));
        return CohortDto.of(c);
    }

    /** The cohort, guarded to the org path (tenant isolation). */
    Cohort require(UUID orgId, UUID cohortId) {
        return cohorts.findById(cohortId)
                .filter(c -> c.getOrgId().equals(orgId))
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));
    }
}
