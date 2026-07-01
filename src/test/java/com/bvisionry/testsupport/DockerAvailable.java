package com.bvisionry.testsupport;

import org.testcontainers.DockerClientFactory;

/**
 * Probes whether a usable Docker daemon is reachable, so integration tests that
 * need Testcontainers can be gated on it instead of being unconditionally
 * disabled. {@link DockerClientFactory#isDockerAvailable()} swallows any
 * connection error and returns {@code false} rather than throwing, so this is
 * safe to call from a JUnit {@code @EnabledIf} condition on machines without
 * Docker (it never triggers container startup).
 *
 * @see EnabledIfDockerAvailable
 */
public final class DockerAvailable {

    private DockerAvailable() {}

    public static boolean isAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }
}
