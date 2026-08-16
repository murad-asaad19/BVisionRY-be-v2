package com.bvisionry.auth.sso;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Platform administration of enterprise SSO registrations.
 *
 * <p>SUPER_ADMIN at both layers, which is the whole authorization model here and
 * is deliberate rather than an omission of tenant scoping: a registration is a
 * PLATFORM record of a domain the platform verified, not org-owned data. Letting
 * an ORG_ADMIN manage their own would mean letting them assert which email domain
 * they control, and a mis-asserted domain is cross-tenant account takeover. The
 * class-level {@code @PreAuthorize} is the method layer; {@code SecurityConfig}
 * carries the matching route floor so a dropped annotation does not open the
 * surface (pinned by {@code SsoRouteSecurityIntegrationTest}).
 */
@RestController
@RequestMapping("/api/admin/sso-registrations")
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SsoRegistrationAdminController {

    private final SsoRegistrationService service;

    // The method names are the springdoc operationIds, and springdoc disambiguates
    // collisions by appending a running counter (`list_11`, `create_22`). Naming
    // these `list`/`create`/`update`/`delete` would therefore renumber every
    // later controller's operationId in the generated web client on the day this
    // shipped -- ~200 lines of diff with no semantic change. Unique names cost
    // nothing and keep the contract diff to the endpoints actually added.
    @GetMapping
    public List<SsoRegistrationResponse> listSsoRegistrations() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<SsoRegistrationResponse> createSsoRegistration(
            @Valid @RequestBody SsoRegistrationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public SsoRegistrationResponse updateSsoRegistration(
            @PathVariable UUID id, @Valid @RequestBody SsoRegistrationRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSsoRegistration(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
