package com.bvisionry.health;

import com.bvisionry.common.security.AuthorizedInSecurityConfig;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness endpoint with no DB/Redis dependency — used by the Docker Compose
 * healthcheck (GET /api/v1/health) and kept stable across the convergence.
 */
@RestController
public class HealthController {

    @AuthorizedInSecurityConfig("permitAll: liveness probe for the Docker Compose healthcheck")
    @GetMapping("/api/v1/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
