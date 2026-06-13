package com.bvisionry.auth;

import com.bvisionry.auth.dto.ChangeUserRoleRequest;
import com.bvisionry.auth.dto.CreateUserRequest;
import com.bvisionry.auth.dto.UpdateUserRequest;
import com.bvisionry.auth.dto.UserResponse;
import com.bvisionry.organization.MoveMemberService;
import com.bvisionry.organization.dto.MoveMemberRequest;
import com.bvisionry.organization.dto.MoveMemberResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
public class UserController {

    private final UserService userService;
    private final MoveMemberService moveMemberService;

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(request));
    }

    /** Platform-wide user listing — powers the super-admin "Platform Admins" view. */
    @GetMapping
    public ResponseEntity<List<UserResponse>> list() {
        return ResponseEntity.ok(userService.list());
    }

    /**
     * Promote a user to (or demote from) SUPER_ADMIN. Lives on /api/users rather
     * than the org-scoped member endpoint because SUPER_ADMIN is a platform role
     * with no organization, and the member endpoint deliberately rejects it.
     */
    @PatchMapping("/{id}/role")
    public ResponseEntity<UserResponse> changeRole(@PathVariable UUID id,
                                                   @Valid @RequestBody ChangeUserRoleRequest request) {
        UUID actorId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(userService.changeRole(id, request, actorId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.getById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> update(@PathVariable UUID id,
                                                @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(userService.update(id, request));
    }

    @PatchMapping("/{id}/suspend")
    public ResponseEntity<UserResponse> suspend(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.suspendUser(id));
    }

    @PatchMapping("/{id}/reinstate")
    public ResponseEntity<UserResponse> reinstate(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.reinstateUser(id));
    }

    /**
     * Move a member (with all their assignments + submissions) from their
     * current organization to a different one. Lives on /api/users rather
     * than /api/organizations/{orgId}/members because the action crosses
     * tenant boundaries — only SUPER_ADMIN can see both sides.
     */
    @PostMapping("/{id}/move-organization")
    public ResponseEntity<MoveMemberResponse> moveOrganization(
            @PathVariable UUID id,
            @Valid @RequestBody MoveMemberRequest request) {
        UUID actorId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(
                moveMemberService.move(id, request.targetOrganizationId(), actorId));
    }
}
