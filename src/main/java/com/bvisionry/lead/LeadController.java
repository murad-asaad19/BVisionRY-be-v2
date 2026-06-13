package com.bvisionry.lead;

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
 * Public endpoint for marketing lead capture (Book-a-Demo modal).
 * No authentication required — permitted via SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/leads")
@RequiredArgsConstructor
public class LeadController {

    private final LeadService leadService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    /**
     * Creates a lead. Because the endpoint is public and CSRF-exempt, it is
     * rate-limited per real client IP (resolved via the trusted-proxy-aware
     * {@link ClientIpResolver}) so a bot cannot flood the database or the sales
     * notification inbox. The bucket key is namespaced with {@code "lead:"} so
     * lead traffic gets its own per-IP window and never collides with other
     * users of the shared anonymous limiter. Exceeding the limit raises
     * {@code RateLimitExceededException}, which the global handler maps to 429.
     */
    @PostMapping
    public ResponseEntity<Map<String, UUID>> create(
            @Valid @RequestBody CreateLeadRequest request,
            HttpServletRequest httpRequest) {
        rateLimitService.checkTryItOutLimit("lead:" + clientIpResolver.resolve(httpRequest));
        UUID id = leadService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }
}
