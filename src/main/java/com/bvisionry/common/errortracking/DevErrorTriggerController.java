package com.bvisionry.common.errortracking;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberate unhandled exception, so error tracking can be falsified end to end
 * rather than assumed: hit it, then query {@code GET /api/v1/error-events} and the
 * row must be there.
 *
 * <p><b>Why it cannot reach production, precisely.</b> {@code @Profile("dev")} is
 * NOT self-evidently safe here: {@code application.properties} line 2 sets
 * {@code spring.profiles.active=dev}, so a plain {@code java -jar} of this artifact
 * WOULD register this bean. The real gate is the deployment artifact —
 * {@code Dockerfile} line 60 sets {@code ENV SPRING_PROFILES_ACTIVE=prod}, which
 * overrides the properties default, and production only ever runs that image. If
 * this app is ever shipped outside that image, this class must be revisited.
 *
 * <p>Defence in depth for exactly that scenario: SUPER_ADMIN only. A probe that
 * crashes a request on demand should not be reachable by an ordinary authenticated
 * user, which {@code anyRequest().authenticated()} alone would have allowed.
 *
 * <p>{@code @Hidden} keeps it out of {@code /v3/api-docs}: the exported spec drives
 * the web app's generated types, and a debug trigger is not part of the contract.
 */
@RestController
@RequestMapping("/api/v1/dev/error-trigger")
@Profile("dev")
@Hidden
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
public class DevErrorTriggerController {

    @GetMapping
    public void boom() {
        throw new IllegalStateException(
                "Deliberate error_tracking probe — backend unhandled exception");
    }
}
