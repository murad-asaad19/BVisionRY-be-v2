package com.bvisionry.auth;

import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.dto.ChangeUserRoleRequest;
import com.bvisionry.auth.dto.CreateUserRequest;
import com.bvisionry.auth.dto.UpdateUserRequest;
import com.bvisionry.auth.dto.UserResponse;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.DuplicateResourceException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.membertype.MemberTypeService;
import com.bvisionry.organization.OrgAuditActions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final MemberTypeService memberTypeService;
    private final AuditService auditService;

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.email().toLowerCase())) {
            throw new DuplicateResourceException("User with email " + request.email() + " already exists");
        }
        User user = new User();
        user.setEmail(request.email().toLowerCase());
        user.setName(request.name());
        user.setRole(UserRole.MEMBER);
        user.setStatus(UserStatus.ACTIVE);
        user.setActivatedAt(Instant.now());
        User saved = userRepository.save(user);
        return UserResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public UserResponse getById(UUID id) {
        User user = findOrThrow(id);
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return userRepository.findAllWithOrganization().stream()
                .map(UserResponse::from)
                .toList();
    }

    /**
     * Promote a user to (or demote from) SUPER_ADMIN. The promoted user keeps
     * their organization — SUPER_ADMIN bypasses org-scope checks, so there's no
     * need to detach them. Guards against demoting the platform's last super
     * admin, which would otherwise lock everyone out of the admin surface.
     */
    @Transactional
    public UserResponse changeRole(UUID id, ChangeUserRoleRequest request, UUID actorId) {
        User user = findOrThrow(id);
        UserRole oldRole = user.getRole();
        UserRole newRole = request.role();
        if (oldRole == newRole) {
            return UserResponse.from(user); // no-op — don't emit an audit row
        }
        if (oldRole == UserRole.SUPER_ADMIN
                && userRepository.countByRole(UserRole.SUPER_ADMIN) <= 1) {
            throw new BadRequestException("Cannot demote the last remaining super admin");
        }
        user.setRole(newRole);
        User saved = userRepository.save(user);
        auditService.log(actorId,
                user.getOrganization() != null ? user.getOrganization().getId() : null,
                OrgAuditActions.USER_ROLE_CHANGED, OrgAuditActions.ENTITY_USER, user.getId(),
                Map.of("oldRole", oldRole.name(), "newRole", newRole.name()));
        return UserResponse.from(saved);
    }

    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest request) {
        User user = findOrThrow(id);
        if (request.name() != null) user.setName(request.name());
        if (request.avatarUrl() != null) user.setAvatarUrl(request.avatarUrl());
        if (request.userType() != null) {
            // Validate the incoming code against the member_types table rather
            // than accepting any string — otherwise a typo would silently orphan
            // the user from every dropdown and filter.
            memberTypeService.requireExists(request.userType());
            user.setUserType(request.userType());
        }
        User saved = userRepository.save(user);
        return UserResponse.from(saved);
    }

    @Transactional
    public UserResponse suspendUser(UUID id) {
        User user = findOrThrow(id);
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new BadRequestException("User is already suspended");
        }
        user.setStatus(UserStatus.SUSPENDED);
        User saved = userRepository.save(user);
        return UserResponse.from(saved);
    }

    @Transactional
    public UserResponse reinstateUser(UUID id) {
        User user = findOrThrow(id);
        if (user.getStatus() != UserStatus.SUSPENDED && user.getStatus() != UserStatus.DEACTIVATED) {
            throw new BadRequestException("User is not suspended or deactivated");
        }
        user.setStatus(UserStatus.ACTIVE);
        User saved = userRepository.save(user);
        return UserResponse.from(saved);
    }

    User findOrThrow(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));
    }
}
