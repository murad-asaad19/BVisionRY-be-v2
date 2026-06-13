package com.bvisionry.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests that need a real PostgreSQL with the app's Flyway
 * migrations applied. Uses the singleton-container pattern: the container starts on
 * first class load and is reused across every test class in the JVM, which is much
 * faster than per-class containers.
 *
 * <p>Why: the {@code test} profile previously ran against H2 in PostgreSQL-compat mode
 * with {@code flyway.enabled=false}, so migrations were never exercised and
 * Postgres-specific SQL (e.g. {@code gen_random_uuid()}, JSONB ops) couldn't be
 * covered. Tests extending this class override the datasource to Postgres and re-enable
 * Flyway so the schema under test matches production.
 *
 * <p>Requires Docker to be running locally.
 */
public abstract class AbstractPostgresIntegrationTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("bvisionry_test")
                    .withUsername("test")
                    .withPassword("test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void overrideDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Let Flyway own the schema; Hibernate should only validate.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }
}
