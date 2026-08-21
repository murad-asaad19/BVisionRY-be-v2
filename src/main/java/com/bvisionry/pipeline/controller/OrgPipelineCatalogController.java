package com.bvisionry.pipeline.controller;

import com.bvisionry.pipeline.dto.PipelineSummaryResponse;
import com.bvisionry.pipeline.service.PipelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * One organisation's published assessment catalogue — what the org console's
 * reporting selectors (Dashboard, Insights, Reports) are allowed to offer.
 *
 * <p>Separate from {@code GET /api/pipelines/published} on purpose. That one
 * answers "what may this CALLER see anywhere", which is the right question for
 * the assigning and authoring pickers and the wrong one for a report about a
 * specific organisation: a SUPER_ADMIN opening an org's dashboard got the whole
 * platform's pipelines, so selecting another tenant's assessment rendered an
 * all-zeros dashboard that read as "nobody has started".
 *
 * <p>Tenancy follows the org-scoped console convention: {@code
 * OrgAccessInterceptor} on the route pattern plus the class
 * {@code @PreAuthorize}, and the service query carries {@code orgId}.
 */
@RestController
@RequestMapping(path = "/api/organizations/{orgId}/pipelines",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@RequiredArgsConstructor
@Tag(name = "Organisation assessment catalogue",
        description = "Published assessments assigned to one organisation.")
public class OrgPipelineCatalogController {

    private final PipelineService pipelineService;

    @GetMapping("/published")
    @Operation(summary = "Published assessments assigned to this organisation",
            description = "Includes the org's sub-orgs, since assignments live there (V136). "
                    + "Empty when the org has none assigned.")
    public ResponseEntity<List<PipelineSummaryResponse>> published(@PathVariable UUID orgId) {
        return ResponseEntity.ok(pipelineService.getPublishedCatalogForOrg(orgId));
    }
}
