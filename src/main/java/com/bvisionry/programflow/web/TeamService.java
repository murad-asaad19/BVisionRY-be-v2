package com.bvisionry.programflow.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.DuplicateResourceException;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.programflow.domain.Team;
import com.bvisionry.programflow.dto.MemberDto;
import com.bvisionry.programflow.dto.TeamsResponse;
import com.bvisionry.programflow.repository.OrgMemberRow;
import com.bvisionry.programflow.repository.TeamRepository;

import lombok.RequiredArgsConstructor;

/** Org-scoped team management: create/rename/delete-empty, move members. */
@Service
@RequiredArgsConstructor
@Transactional
public class TeamService {

    private final TeamRepository teams;

    @Transactional(readOnly = true)
    public TeamsResponse list(UUID orgId) {
        List<Team> orgTeams = teams.findByOrgIdOrderByCreatedAtAsc(orgId);
        List<OrgMemberRow> members = teams.findOrgMembers(orgId);
        Map<UUID, String> teamNames = orgTeams.stream()
                .collect(Collectors.toMap(Team::getId, Team::getName));

        Map<UUID, List<MemberDto>> byTeam = members.stream()
                .filter(m -> m.getTeamId() != null)
                .map(m -> toDto(m, teamNames))
                .collect(Collectors.groupingBy(MemberDto::teamId));

        return new TeamsResponse(
                orgTeams.stream()
                        .map(t -> new TeamsResponse.TeamDto(t.getId(), t.getName(),
                                byTeam.getOrDefault(t.getId(), List.of())))
                        .toList(),
                members.stream()
                        .filter(m -> m.getTeamId() == null)
                        .map(m -> toDto(m, teamNames))
                        .toList());
    }

    public TeamsResponse.TeamDto create(UUID orgId, String name) {
        if (teams.existsByOrgIdAndNameIgnoreCase(orgId, name)) {
            throw new DuplicateResourceException("A team with this name already exists");
        }
        Team t = new Team();
        t.setOrgId(orgId);
        t.setName(name);
        t = teams.save(t);
        return new TeamsResponse.TeamDto(t.getId(), t.getName(), List.of());
    }

    public TeamsResponse.TeamDto rename(UUID orgId, UUID teamId, String name) {
        Team t = requireTeam(orgId, teamId);
        if (!t.getName().equalsIgnoreCase(name) && teams.existsByOrgIdAndNameIgnoreCase(orgId, name)) {
            throw new DuplicateResourceException("A team with this name already exists");
        }
        t.setName(name);
        Map<UUID, String> teamNames = Map.of(t.getId(), t.getName());
        List<MemberDto> members = teams.findOrgMembers(orgId).stream()
                .filter(m -> teamId.equals(m.getTeamId()))
                .map(m -> toDto(m, teamNames))
                .toList();
        return new TeamsResponse.TeamDto(t.getId(), t.getName(), members);
    }

    public void delete(UUID orgId, UUID teamId) {
        Team t = requireTeam(orgId, teamId);
        if (teams.countMembers(teamId) > 0) {
            throw new IllegalOperationException("Only empty teams can be deleted");
        }
        teams.delete(t);
    }

    /** Adds the member to this team, moving them off their previous team if any. */
    public void assignMember(UUID orgId, UUID teamId, UUID userId) {
        requireTeam(orgId, teamId);
        boolean isOrgMember = teams.findOrgMembers(orgId).stream()
                .anyMatch(m -> m.getId().equals(userId));
        if (!isOrgMember) {
            throw new BadRequestException("User is not an active member of this organization");
        }
        teams.removeMembership(userId);
        teams.addMembership(userId, teamId);
    }

    /** Removes the member from whichever team they are on (back to the unassigned pool). */
    public void unassignMember(UUID orgId, UUID userId) {
        boolean isOrgMember = teams.findOrgMembers(orgId).stream()
                .anyMatch(m -> m.getId().equals(userId));
        if (!isOrgMember) {
            throw new BadRequestException("User is not an active member of this organization");
        }
        teams.removeMembership(userId);
    }

    private Team requireTeam(UUID orgId, UUID teamId) {
        return teams.findById(teamId)
                .filter(t -> t.getOrgId().equals(orgId))
                .orElseThrow(() -> new ResourceNotFoundException("Team", teamId.toString()));
    }

    private static MemberDto toDto(OrgMemberRow m, Map<UUID, String> teamNames) {
        return new MemberDto(m.getId(), m.getName(), m.getEmail(), m.getTeamId(),
                m.getTeamId() == null ? null : teamNames.get(m.getTeamId()));
    }
}
