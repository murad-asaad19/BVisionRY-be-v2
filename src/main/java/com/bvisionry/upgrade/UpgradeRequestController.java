package com.bvisionry.upgrade;

import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.upgrade.UpgradePromptLoader.UpgradePrompt;
import com.bvisionry.upgrade.dto.UpgradeRequestCreateRequest;
import com.bvisionry.upgrade.dto.UpgradeRequestResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/upgrade-requests")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class UpgradeRequestController {

    private final UpgradeRequestService service;
    private final UpgradePromptService promptService;

    /**
     * Create a new request. The eligibility/cooldown checks live in the service
     * so the same logic protects any future internal caller.
     *
     * <p>Returns 429 when an active cooldown is in effect (handled by the global
     * exception handler's {@code RateLimitExceededException} mapping).
     */
    @PostMapping
    public ResponseEntity<UpgradeRequestResponse> create(
            @Valid @RequestBody UpgradeRequestCreateRequest req) {
        UpgradeRequestResponse created = service.create(SecurityUtils.getCurrentUserId(), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Returns the caller's most recent request, or 204 No Content if they've
     * never requested. The gate UI uses this on mount to decide between the
     * idle "Request upgrade" button and the cooldown pill.
     */
    @GetMapping("/latest")
    public ResponseEntity<UpgradeRequestResponse> latest() {
        return service.findLatestForCurrentUser(SecurityUtils.getCurrentUserId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Effective prompt copy + cooldown duration the gate UI renders. Comes
     * from the SUPER_ADMIN override row when present, otherwise the shipped
     * properties-file defaults. Edits via the admin UI surface here on the
     * next member fetch.
     */
    @GetMapping("/text")
    public ResponseEntity<UpgradePrompt> text() {
        return ResponseEntity.ok(promptService.get());
    }
}
