package com.bvisionry.leadmagnet;

import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.common.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Public endpoint for the lead-magnet modal ("the science behind the 11
 * pillars") on the marketing Platform page. No authentication required —
 * permitted via SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/lead-magnet")
@RequiredArgsConstructor
public class LeadMagnetController {

    private final LeadMagnetService leadMagnetService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    /**
     * Captures the email and emails the visitor the configured PDF. Public and
     * CSRF-exempt, so it is rate-limited per real client IP (namespaced
     * {@code "lead-magnet:"}) to stop a bot flooding the table or the mailer.
     */
    @PostMapping
    public ResponseEntity<Map<String, UUID>> create(
            @Valid @RequestBody CreateLeadMagnetRequest request,
            HttpServletRequest httpRequest) {
        rateLimitService.checkLeadMagnetLimit("lead-magnet:" + clientIpResolver.resolve(httpRequest));
        UUID id = leadMagnetService.submit(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }
}
