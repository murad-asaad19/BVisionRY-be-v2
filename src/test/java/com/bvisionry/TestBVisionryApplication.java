package com.bvisionry;

import org.springframework.boot.SpringApplication;

/**
 * Canonical Spring Boot 4 entry point for {@code mvn spring-boot:test-run}
 * (see <a href="https://docs.spring.io/spring-boot/maven-plugin/run.html#run.test-variant">
 * Spring Boot Maven plugin docs — Test Variant</a>).
 *
 * <p>The {@code test-run} goal looks for a main class in the test source set
 * before falling back to the production main. Putting one here makes the
 * dispatch unambiguous: e2e launches use this class, prod launches still use
 * {@link BVisionryApplication}.
 *
 * <p>The {@code @Profile("e2e")}-gated configurations under {@code com.bvisionry.e2e}
 * activate based on {@code spring.profiles.active=e2e} (set in
 * {@code application-e2e.properties}), so we don't need {@code SpringApplication.from(...).with(...)}.
 */
public class TestBVisionryApplication {

    public static void main(String[] args) {
        SpringApplication.from(BVisionryApplication::main).run(args);
    }
}
