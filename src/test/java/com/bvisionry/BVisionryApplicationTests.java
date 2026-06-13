package com.bvisionry;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Disabled("Needs a real Postgres + Docker (via AbstractPostgresIntegrationTest). "
        + "Re-enable on a Linux runner / CI where Testcontainers connects to "
        + "/var/run/docker.sock without manual config. On Windows + Docker "
        + "Desktop, the named-pipe API returns a 400 redirect that docker-java "
        + "cannot follow, and TCP exposure plus DOCKER_API_VERSION=1.43 is "
        + "needed locally.")
class BVisionryApplicationTests {

    @Test
    void contextLoads() {
    }
}
