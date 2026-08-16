package com.bvisionry.architecture;

import com.bvisionry.common.security.ExportNameGuard;
import com.bvisionry.common.security.NamesVisibleToSelf;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.bvisionry.architecture.ArchitectureRulesTest.showNamesHandlersMustResolveToTheExportNameGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Falsification for {@link ArchitectureRulesTest#showNamesHandlersMustResolveToTheExportNameGuard}.
 *
 * <p>A green architecture rule proves nothing on its own — it is equally green
 * when it silently matches no methods at all, and this rule has a real way to
 * end up there: it reads PARAMETER NAMES, which only exist in the bytecode
 * because the build compiles with {@code -parameters}. Lose that flag and every
 * name reads {@code arg0}, the predicate matches nothing, and the rule passes
 * vacuously while eleven export handlers go unchecked.
 *
 * <p>{@link #failsForABareShowNamesHandler()} is what stands in the way: the
 * fixture is compiled by the same compiler with the same flags as production,
 * so if the flag ever goes, this test stops seeing a violation and goes red.
 *
 * <p>The fixtures are {@code abstract} on purpose, following
 * {@code RequestHandlerAuthorizationRuleTest}: they carry real
 * {@code @RestController} / {@code @GetMapping} annotations so the rule treats
 * them exactly as it treats production code, and Spring's component scan skips
 * abstract classes — otherwise these would register as live controllers in every
 * {@code @SpringBootTest} and leak into the exported OpenAPI schema.
 */
class ShowNamesGuardRuleTest {

    /** THE falsification: a bare showNames param must be reported, or the rule is theatre. */
    @Test
    void failsForABareShowNamesHandler() {
        assertThatThrownBy(() -> check(BareShowNamesController.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("BareShowNamesController.export(boolean)")
                .hasMessageContaining("takes a boolean showNames but never calls")
                .hasMessageContaining("is not marked @NamesVisibleToSelf");
    }

    /**
     * The one-character evasion: box the flag. Spring binds {@code Boolean} the
     * same way, so the rule has to see it the same way.
     */
    @Test
    void failsForABareBoxedShowNamesHandler() {
        assertThatThrownBy(() -> check(BoxedShowNamesController.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("BoxedShowNamesController.export(java.lang.Boolean)");
    }

    @Test
    void passesWhenTheHandlerCallsTheGuard() {
        assertThatCode(() -> check(GuardedController.class)).doesNotThrowAnyException();
    }

    @Test
    void passesWhenTheHandlerIsMarkedNamesVisibleToSelf() {
        assertThatCode(() -> check(SelfOnlyController.class)).doesNotThrowAnyException();
    }

    /**
     * A stated ceiling, pinned so it is a known gap rather than a surprise: the
     * rule keys off the parameter NAME, so a handler that spells the same flag
     * {@code revealNames} is not matched at all. Documented on the rule; if this
     * ever needs closing, the fix is a name allowlist, not a silent widening.
     */
    @Test
    void doesNotSeeTheSameFlagUnderADifferentName() {
        assertThatCode(() -> checkAllowingNoMatches(RenamedFlagController.class))
                .doesNotThrowAnyException();
    }

    /** The marker is useless if it cannot carry the reason, so pin that it does. */
    @Test
    void theMarkerRecordsAReason() throws Exception {
        NamesVisibleToSelf marker = SelfOnlyController.class
                .getDeclaredMethod("export", boolean.class)
                .getAnnotation(NamesVisibleToSelf.class);

        assertThat(marker.value()).isEqualTo("fixture: the row is pinned to the caller");
    }

    private static void check(Class<?> fixture) {
        JavaClasses imported = new ClassFileImporter().importClasses(fixture, ExportNameGuard.class);
        showNamesHandlersMustResolveToTheExportNameGuard.check(imported);
    }

    /**
     * For the fixture the rule is expected to IGNORE. ArchUnit fails a rule whose
     * {@code that(...)} clause matched nothing, which here would be
     * indistinguishable from the violation we are asserting is absent.
     */
    private static void checkAllowingNoMatches(Class<?> fixture) {
        JavaClasses imported = new ClassFileImporter().importClasses(fixture, ExportNameGuard.class);
        showNamesHandlersMustResolveToTheExportNameGuard.allowEmptyShould(true).check(imported);
    }

    // ---------------------------------------------------------------------
    // Fixtures — abstract so Spring's component scan ignores them (see Javadoc).
    // ---------------------------------------------------------------------

    @RestController
    abstract static class BareShowNamesController {
        @GetMapping("/architecture-fixture/bare-show-names")
        String export(@RequestParam(defaultValue = "false") boolean showNames) {
            return String.valueOf(showNames);
        }
    }

    @RestController
    abstract static class BoxedShowNamesController {
        @GetMapping("/architecture-fixture/boxed-show-names")
        String export(@RequestParam(defaultValue = "false") Boolean showNames) {
            return String.valueOf(showNames);
        }
    }

    @RestController
    abstract static class GuardedController {
        @GetMapping("/architecture-fixture/guarded-show-names")
        String export(@RequestParam(defaultValue = "false") boolean showNames) {
            ExportNameGuard.checkShowNames(showNames);
            return String.valueOf(showNames);
        }
    }

    @RestController
    abstract static class SelfOnlyController {
        @NamesVisibleToSelf("fixture: the row is pinned to the caller")
        @GetMapping("/architecture-fixture/self-only-show-names")
        String export(@RequestParam(defaultValue = "true") boolean showNames) {
            return String.valueOf(showNames);
        }
    }

    @RestController
    abstract static class RenamedFlagController {
        @GetMapping("/architecture-fixture/renamed-flag")
        String export(@RequestParam(defaultValue = "false") boolean revealNames) {
            return String.valueOf(revealNames);
        }
    }
}
