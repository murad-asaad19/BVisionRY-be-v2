package com.bvisionry.architecture;

import com.bvisionry.common.security.AuthorizedInSecurityConfig;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AliasFor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static com.bvisionry.architecture.ArchitectureRulesTest.requestHandlersMustResolveToAnAuthorizationAnnotation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Falsification for {@link ArchitectureRulesTest#requestHandlersMustResolveToAnAuthorizationAnnotation}.
 *
 * <p>A green architecture rule proves nothing on its own — it is equally green
 * when it silently matches no methods at all. These tests run the real rule
 * object against hand-built controllers and pin both directions: a handler with
 * no authorization annotation and no marker FAILS, and each of the two accepted
 * forms PASSES.
 *
 * <p>The fixtures are {@code abstract} on purpose. They carry real
 * {@code @RestController} / {@code @GetMapping} annotations so the rule treats
 * them exactly as it treats production code, and Spring's component scan skips
 * abstract classes — otherwise these would be registered as live controllers in
 * every {@code @SpringBootTest} and leak into the exported OpenAPI schema.
 * ({@code ArchitectureRulesTest} itself never sees them: its
 * {@code @AnalyzeClasses} uses {@code DoNotIncludeTests}.)
 */
class RequestHandlerAuthorizationRuleTest {

    @Test
    void failsForAHandlerWithNoAuthorizationAnnotationAndNoMarker() {
        assertThatThrownBy(() -> check(BareController.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("BareController.bare()")
                .hasMessageContaining("has no @PreAuthorize")
                .hasMessageContaining("is not marked @AuthorizedInSecurityConfig");
    }

    /**
     * The evasion the meta-aware mapping check exists to close: Spring routes a
     * composed annotation, so a direct-only check would let this handler through
     * as "not a handler at all" rather than "an unauthorized handler".
     */
    @Test
    void failsForABareHandlerBehindAComposedMappingAnnotation() {
        assertThatThrownBy(() -> check(ComposedMappingController.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ComposedMappingController.composed()");
    }

    @Test
    void passesWhenTheHandlerIsExplicitlyMarkedAsRouteLayerOnly() {
        assertThatCode(() -> check(MarkedController.class)).doesNotThrowAnyException();
    }

    @Test
    void passesWhenAuthorizationIsInheritedFromTheController() {
        assertThatCode(() -> check(ClassAnnotatedController.class)).doesNotThrowAnyException();
    }

    /** The marker is useless if it cannot carry the reason, so pin that it does. */
    @Test
    void theMarkerRecordsAReason() throws Exception {
        AuthorizedInSecurityConfig marker = MarkedController.class
                .getDeclaredMethod("marked")
                .getAnnotation(AuthorizedInSecurityConfig.class);

        assertThat(marker.value()).isEqualTo("permitAll: fixture");
    }

    private static void check(Class<?> fixture) {
        JavaClasses imported = new ClassFileImporter().importClasses(fixture);
        requestHandlersMustResolveToAnAuthorizationAnnotation.check(imported);
    }

    // ---------------------------------------------------------------------
    // Fixtures — abstract so Spring's component scan ignores them (see Javadoc).
    // ---------------------------------------------------------------------

    @RestController
    abstract static class BareController {
        @GetMapping("/architecture-fixture/bare")
        abstract String bare();
    }

    @RestController
    abstract static class MarkedController {
        @AuthorizedInSecurityConfig("permitAll: fixture")
        @GetMapping("/architecture-fixture/marked")
        abstract String marked();
    }

    @RestController
    @PreAuthorize("isAuthenticated()")
    abstract static class ClassAnnotatedController {
        @GetMapping("/architecture-fixture/class-annotated")
        abstract String inherited();
    }

    /**
     * A composed mapping annotation, built the same way Spring builds
     * {@code @GetMapping} itself — meta-annotated with {@code @RequestMapping},
     * which is the only mapping annotation whose {@code @Target} includes
     * {@code TYPE} and can therefore sit on an annotation declaration.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @RequestMapping(method = RequestMethod.GET)
    @interface ComposedGetMapping {
        @AliasFor(annotation = RequestMapping.class, attribute = "path")
        String[] value() default {};
    }

    @RestController
    abstract static class ComposedMappingController {
        @ComposedGetMapping("/architecture-fixture/composed")
        abstract String composed();
    }
}
