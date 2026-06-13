package com.bvisionry.aicalllog.controller;

import com.bvisionry.aicalllog.dto.AICallLogResponse;
import com.bvisionry.aicalllog.service.AICallLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/ai-config/call-logs")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
public class AICallLogController {

    private final AICallLogService callLogService;

    /**
     * List AI call logs, most-recent first. Any combination of filters may be
     * supplied; pass none to browse everything. Results paginated — sane
     * defaults so the UI doesn't have to enforce them.
     *
     * UUID params are parsed defensively: garbage input (template placeholders
     * from Swagger, stray pastes in the submission-ID field) is treated as
     * "no filter" rather than a 500 error.
     */
    @GetMapping
    public ResponseEntity<Page<AICallLogResponse>> list(
            @RequestParam(required = false) String pipelineId,
            @RequestParam(required = false) String submissionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int cappedSize = Math.min(Math.max(size, 1), 200);
        return ResponseEntity.ok(callLogService.find(
                parseUuidOrNull(pipelineId),
                parseUuidOrNull(submissionId),
                page,
                cappedSize));
    }

    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
