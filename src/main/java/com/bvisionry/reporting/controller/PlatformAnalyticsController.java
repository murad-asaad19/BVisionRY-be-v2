package com.bvisionry.reporting.controller;

import com.bvisionry.reporting.dto.PlatformAnalyticsResponse;
import com.bvisionry.reporting.service.PlatformAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/analytics")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
public class PlatformAnalyticsController {

    private final PlatformAnalyticsService platformAnalyticsService;

    /**
     * Platform analytics for Super Admin.
     * Total orgs, users, submissions, completion rate, avg scores.
     */
    @GetMapping
    public ResponseEntity<PlatformAnalyticsResponse> getAnalytics() {
        return ResponseEntity.ok(platformAnalyticsService.getAnalytics());
    }
}
