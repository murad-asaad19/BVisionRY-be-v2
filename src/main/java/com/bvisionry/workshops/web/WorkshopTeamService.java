package com.bvisionry.workshops.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.event.WorkshopEvents;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.DuplicateResourceException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.workshops.domain.WorkshopExerciseRun;
import com.bvisionry.workshops.domain.WorkshopTeam;
import com.bvisionry.workshops.dto.AssignMemberRequest;
import com.bvisionry.workshops.dto.TeamNameRequest;
import com.bvisionry.workshops.dto.WorkshopTeamsResponse;
import com.bvisionry.workshops.repository.WorkshopExerciseRunRepository;
import com.bvisionry.workshops.repository.WorkshopRepository;
import com.bvisionry.workshops.repository.WorkshopTaskSubmissionRepository;
import com.bvisionry.workshops.repository.WorkshopTeamRepository;

import lombok.RequiredArgsConstructor;

/**
 * Per-workshop team management: team CRUD, member assignment, the
 * admin-selected lead, and the join-link auto-assignment (least-filled team,
 * ties by team order — sequential fill keeps team sizes equal).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class WorkshopTeamService {

    private final WorkshopTeamRepository teams;
    private final WorkshopAdminService admin;
    private final WorkshopExerciseRunRepository runs;
    private final WorkshopTaskSubmissionRepository submissions;
    private final WorkshopRepository workshops;

    @Transactional(readOnly = true)
    public WorkshopTeamsResponse teams(UUID orgId, UUID workshopId) {
        admin.requireWorkshop(orgId, workshopId);
        Map<UUID, List<WorkshopTeamsResponse.MemberDto>> byTeam = new LinkedHashMap<>();
        List<WorkshopTeamsResponse.MemberDto> unassigned = new ArrayList<>();
        for (WorkshopTeamRepository.WorkshopMemberRow row : teams.findOrgMembers(orgId, workshopId)) {
            WorkshopTeamsResponse.MemberDto dto = new WorkshopTeamsResponse.MemberDto(
                    row.getId(), row.getName(), row.getEmail(), row.getLead());
            if (row.getTeamId() == null) {
                unassigned.add(dto);
            } else {
                byTeam.computeIfAbsent(row.getTeamId(), k -> new ArrayList<>()).add(dto);
            }
        }
        List<WorkshopTeamsResponse.TeamDto> teamDtos =
                teams.findByWorkshopIdOrderByPositionAscCreatedAtAsc(workshopId).stream()
                        .map(t -> new WorkshopTeamsResponse.TeamDto(
                                t.getId(), t.getName(), t.getCard(),
                                byTeam.getOrDefault(t.getId(), List.of())))
                        .toList();
        return new WorkshopTeamsResponse(teamDtos, unassigned);
    }

    public void createTeam(UUID orgId, UUID workshopId, TeamNameRequest req) {
        admin.requireWorkshop(orgId, workshopId);
        if (teams.existsByWorkshopIdAndNameIgnoreCase(workshopId, req.name().trim())) {
            throw new DuplicateResourceException("A team with this name already exists");
        }
        WorkshopTeam t = new WorkshopTeam();
        t.setWorkshopId(workshopId);
        t.setName(req.name().trim());
        t.setPosition(teams.nextPosition(workshopId));
        teams.save(t);
    }

    public void renameTeam(UUID orgId, UUID workshopId, UUID teamId, TeamNameRequest req) {
        WorkshopTeam t = requireTeam(orgId, workshopId, teamId);
        t.setName(req.name().trim());
        teams.save(t);
    }

    /** Deleting a team cascades its membership, runs and submissions (DB FKs). */
    public void deleteTeam(UUID orgId, UUID workshopId, UUID teamId) {
        teams.delete(requireTeam(orgId, workshopId, teamId));
    }

    public void assignMember(UUID orgId, UUID workshopId, AssignMemberRequest req) {
        admin.requireWorkshop(orgId, workshopId);
        if (req.teamId() != null) {
            requireTeam(orgId, workshopId, req.teamId());
        }
        teams.removeMembership(workshopId, req.userId());
        if (req.teamId() != null) {
            teams.addMembership(workshopId, req.userId(), req.teamId());
        }
    }

    public void setCard(UUID orgId, UUID workshopId, UUID teamId, String card) {
        WorkshopTeam t = requireTeam(orgId, workshopId, teamId);
        t.setCard(card);
        teams.save(t);
    }

    /** Clears the team's open "needs help" ping — the next timer click can ping again. */
    public void dismissHelp(UUID orgId, UUID workshopId, UUID teamId) {
        WorkshopTeam t = requireTeam(orgId, workshopId, teamId);
        t.setHelpRequestedAt(null);
        teams.save(t);
    }

    public void setLead(UUID orgId, UUID workshopId, UUID teamId, UUID userId) {
        requireTeam(orgId, workshopId, teamId);
        teams.clearLead(teamId);
        if (teams.setLead(teamId, userId) == 0) {
            throw new BadRequestException("User is not a member of this team");
        }
    }

    /**
     * Wipe one member's answers across the workshop so they can redo their
     * tasks. If the member leads a team, the team's runs are un-shared too —
     * the member gate re-locks until the lead re-completes and re-shares.
     * Dealt card hands stay pinned on the runs.
     */
    public void resetMemberAnswers(UUID orgId, UUID workshopId, UUID userId) {
        admin.requireWorkshop(orgId, workshopId);
        submissions.deleteByWorkshopIdAndUserId(workshopId, userId);
        // Re-lock the intro-survey gate too, so a reset member re-takes it before tasks.
        workshops.deleteIntroResponseByWorkshopIdAndUserId(workshopId, userId);
        for (WorkshopTeamRepository.WorkshopMemberRow row : teams.findOrgMembers(orgId, workshopId)) {
            if (row.getId().equals(userId) && row.getLead() && row.getTeamId() != null) {
                List<WorkshopExerciseRun> teamRuns = runs.findByTeamId(row.getTeamId());
                teamRuns.forEach(r -> r.setSharedAt(null));
                runs.saveAll(teamRuns);
            }
        }
    }

    /**
     * A member joined the org through this workshop's join link: drop them
     * onto the least-filled team. No teams prepared yet → they stay unassigned
     * and surface in the admin's unassigned pool.
     * ponytail: concurrent joins may momentarily unbalance by one; a lock is
     * not worth it for workshop-sized cohorts.
     */
    @EventListener
    public void onJoinedViaLink(WorkshopEvents.JoinedViaLink event) {
        List<WorkshopTeamRepository.TeamFillRow> fill = teams.findTeamFill(event.workshopId());
        if (fill.isEmpty()) {
            return;
        }
        teams.removeMembership(event.workshopId(), event.userId());
        teams.addMembership(event.workshopId(), event.userId(), fill.get(0).getTeamId());
    }

    private WorkshopTeam requireTeam(UUID orgId, UUID workshopId, UUID teamId) {
        admin.requireWorkshop(orgId, workshopId);
        WorkshopTeam t = teams.findById(teamId)
                .orElseThrow(() -> new ResourceNotFoundException("Team", teamId.toString()));
        if (!t.getWorkshopId().equals(workshopId)) {
            throw new ResourceNotFoundException("Team", teamId.toString());
        }
        return t;
    }
}
