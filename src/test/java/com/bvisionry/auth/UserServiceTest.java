package com.bvisionry.auth;

import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.dto.ChangeUserRoleRequest;
import com.bvisionry.auth.dto.UserResponse;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.membertype.MemberTypeService;
import com.bvisionry.organization.entity.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the platform-level role promote/demote path
 * ({@link UserService#changeRole}). Mocks the repository + audit service so
 * each scenario exercises only the guard logic, not the DB.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private MemberTypeService memberTypeService;
    @Mock private AuditService auditService;

    @InjectMocks private UserService userService;

    private UUID actorId;

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID();
    }

    private User userWith(UserRole role, Organization org) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail("user@example.com");
        u.setName("Test User");
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setOrganization(org);
        return u;
    }

    @Test
    void changeRole_promotesMemberToSuperAdmin_keepingOrganization() {
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("Acme");
        User user = userWith(UserRole.MEMBER, org);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponse resp = userService.changeRole(
                user.getId(), new ChangeUserRoleRequest(UserRole.SUPER_ADMIN), actorId);

        assertThat(resp.role()).isEqualTo(UserRole.SUPER_ADMIN);
        assertThat(user.getRole()).isEqualTo(UserRole.SUPER_ADMIN);
        assertThat(user.getOrganization()).isSameAs(org); // promotion keeps the org
        verify(userRepository).save(user);
        verify(auditService).log(eq(actorId), eq(org.getId()), anyString(), anyString(),
                eq(user.getId()), any());
    }

    @Test
    void changeRole_demotesSuperAdmin_whenOtherAdminsRemain() {
        User user = userWith(UserRole.SUPER_ADMIN, null);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.countByRole(UserRole.SUPER_ADMIN)).thenReturn(2L);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponse resp = userService.changeRole(
                user.getId(), new ChangeUserRoleRequest(UserRole.ORG_ADMIN), actorId);

        assertThat(resp.role()).isEqualTo(UserRole.ORG_ADMIN);
        verify(userRepository).save(user);
    }

    @Test
    void changeRole_rejectsDemotingLastSuperAdmin() {
        User user = userWith(UserRole.SUPER_ADMIN, null);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.countByRole(UserRole.SUPER_ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> userService.changeRole(
                user.getId(), new ChangeUserRoleRequest(UserRole.MEMBER), actorId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("last remaining super admin");

        assertThat(user.getRole()).isEqualTo(UserRole.SUPER_ADMIN); // unchanged
        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void changeRole_isNoOp_whenRoleUnchanged() {
        User user = userWith(UserRole.MEMBER, null);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        UserResponse resp = userService.changeRole(
                user.getId(), new ChangeUserRoleRequest(UserRole.MEMBER), actorId);

        assertThat(resp.role()).isEqualTo(UserRole.MEMBER);
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).countByRole(any());
        verifyNoInteractions(auditService);
    }
}
