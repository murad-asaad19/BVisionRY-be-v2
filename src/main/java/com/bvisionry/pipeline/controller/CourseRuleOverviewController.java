package com.bvisionry.pipeline.controller;

import com.bvisionry.pipeline.dto.CourseRuleOverviewResponse;
import com.bvisionry.pipeline.dto.UpdateCourseRuleModeRequest;
import com.bvisionry.pipeline.service.CourseRuleOverviewService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Platform console → the AI suggestion rules table beside Course visibility
 * (spec §2.5).
 *
 * <p>Under {@code /api/admin/**} rather than {@code /api/pipelines/**} because
 * this is a cross-pipeline overview, not a pipeline's own config; SUPER_ADMIN
 * either way, restated on each handler (Spring REPLACES class-level rules, so
 * restatement is the safe idiom this codebase already uses).
 */
@RestController
@RequestMapping(path = "/api/admin/course-rules", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Course rules (platform)", description = "Cross-pipeline AI course rules and their Suggest/Auto-assign mode.")
public class CourseRuleOverviewController {

    private final CourseRuleOverviewService service;

    @GetMapping
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public List<CourseRuleOverviewResponse> list() {
        return service.list();
    }

    @PutMapping("/{ruleId}/mode")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<Void> setMode(@PathVariable UUID ruleId,
                                        @Valid @RequestBody UpdateCourseRuleModeRequest request) {
        service.setMode(ruleId, request);
        return ResponseEntity.noContent().build();
    }
}
