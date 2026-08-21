package com.bvisionry.assessment;

import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.assessment.dto.AutoAssignmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{orgId}/auto-assignments")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
public class AutoAssignmentController {

    private final CurrentUserAccessor currentUser;
    private final PipelineAutoAssignmentService autoAssignmentService;

    @GetMapping
    public ResponseEntity<List<AutoAssignmentResponse>> listRules(@PathVariable UUID orgId) {
        return ResponseEntity.ok(autoAssignmentService.listRules(orgId));
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> deleteRule(@PathVariable UUID orgId, @PathVariable UUID ruleId) {
        autoAssignmentService.deleteRule(orgId, ruleId, currentUser.require().userId());
        return ResponseEntity.noContent().build();
    }
}
