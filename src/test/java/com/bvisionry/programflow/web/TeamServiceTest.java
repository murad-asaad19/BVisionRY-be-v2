package com.bvisionry.programflow.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.DuplicateResourceException;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.programflow.domain.Team;
import com.bvisionry.programflow.repository.OrgMemberRow;
import com.bvisionry.programflow.repository.TeamRepository;

@ExtendWith(MockitoExtension.class)
class TeamServiceTest {

    @Mock
    private TeamRepository teams;

    private TeamService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID teamId = UUID.randomUUID();
    private Team team;

    @BeforeEach
    void setUp() {
        service = new TeamService(teams);
        team = new Team();
        team.setId(teamId);
        team.setOrgId(orgId);
        team.setName("Team Falcon");
    }

    private static OrgMemberRow member(UUID id) {
        return new OrgMemberRow() {
            @Override
            public UUID getId() {
                return id;
            }

            @Override
            public String getName() {
                return "Member";
            }

            @Override
            public String getEmail() {
                return "m@example.com";
            }

            @Override
            public UUID getTeamId() {
                return null;
            }
        };
    }

    @Test
    void createRejectsDuplicateNames() {
        when(teams.existsByOrgIdAndNameIgnoreCase(orgId, "Team Falcon")).thenReturn(true);
        assertThatThrownBy(() -> service.create(orgId, "Team Falcon"))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void deleteRejectsNonEmptyTeams() {
        when(teams.findById(teamId)).thenReturn(Optional.of(team));
        when(teams.countMembers(teamId)).thenReturn(3L);
        assertThatThrownBy(() -> service.delete(orgId, teamId))
                .isInstanceOf(IllegalOperationException.class);
    }

    @Test
    void assignMovesTheMemberOffTheirPreviousTeamFirst() {
        UUID userId = UUID.randomUUID();
        when(teams.findById(teamId)).thenReturn(Optional.of(team));
        when(teams.findOrgMembers(orgId)).thenReturn(List.of(member(userId)));

        service.assignMember(orgId, teamId, userId);

        InOrder inOrder = Mockito.inOrder(teams);
        inOrder.verify(teams).removeMembership(userId);
        inOrder.verify(teams).addMembership(userId, teamId);
    }

    @Test
    void assignRejectsUsersOutsideTheOrg() {
        when(teams.findById(teamId)).thenReturn(Optional.of(team));
        when(teams.findOrgMembers(orgId)).thenReturn(List.of());
        assertThatThrownBy(() -> service.assignMember(orgId, teamId, UUID.randomUUID()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void unassignRemovesMembership() {
        UUID userId = UUID.randomUUID();
        when(teams.findOrgMembers(orgId)).thenReturn(List.of(member(userId)));
        service.unassignMember(orgId, userId);
        verify(teams).removeMembership(userId);
    }

    @Test
    void listGroupsMembersAndUnassignedPool() {
        UUID assignedId = UUID.randomUUID();
        UUID unassignedId = UUID.randomUUID();
        OrgMemberRow assigned = new OrgMemberRow() {
            @Override
            public UUID getId() {
                return assignedId;
            }

            @Override
            public String getName() {
                return "Sara";
            }

            @Override
            public String getEmail() {
                return "s@example.com";
            }

            @Override
            public UUID getTeamId() {
                return teamId;
            }
        };
        when(teams.findByOrgIdOrderByCreatedAtAsc(orgId)).thenReturn(List.of(team));
        when(teams.findOrgMembers(orgId)).thenReturn(List.of(assigned, member(unassignedId)));

        var response = service.list(orgId);

        assertThat(response.teams()).hasSize(1);
        assertThat(response.teams().get(0).members()).extracting("id").containsExactly(assignedId);
        assertThat(response.teams().get(0).members().get(0).teamName()).isEqualTo("Team Falcon");
        assertThat(response.unassigned()).extracting("id").containsExactly(unassignedId);
    }
}
