package com.bvisionry.contact;

import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.common.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoint for the marketing "Contact Us" form.
 * No authentication required — permitted via SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/contact")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService contactService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    /**
     * Submits a contact message. Because the endpoint is public and CSRF-exempt,
     * it is rate-limited per real client IP (resolved via the trusted-proxy-aware
     * {@link ClientIpResolver}) so a bot cannot flood the notification inbox. The
     * bucket key is namespaced with {@code "contact:"} so contact traffic gets its
     * own per-IP window and never collides with other users of the shared
     * anonymous limiter. Exceeding the limit raises
     * {@code RateLimitExceededException}, which the global handler maps to 429.
     */
    @PostMapping
    public ResponseEntity<Void> submit(
            @Valid @RequestBody ContactRequest request,
            HttpServletRequest httpRequest) {
        rateLimitService.checkTryItOutLimit("contact:" + clientIpResolver.resolve(httpRequest));
        contactService.submit(request);
        return ResponseEntity.accepted().build();
    }
}
