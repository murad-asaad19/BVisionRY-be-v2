package com.bvisionry.testsupport;

import org.junit.jupiter.api.condition.EnabledIf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables the annotated test class/method only when a Docker daemon is
 * reachable, so Testcontainers-backed integration tests actually run on CI
 * (and any dev machine with Docker) while being cleanly skipped — not silently
 * disabled — where Docker is absent. Replaces the previous unconditional
 * {@code @Disabled} on integration tests, which never ran anywhere and left the
 * behavior they assert without regression coverage.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@EnabledIf(
        value = "com.bvisionry.testsupport.DockerAvailable#isAvailable",
        disabledReason = "No Docker daemon reachable — Testcontainers integration test skipped")
public @interface EnabledIfDockerAvailable {
}
