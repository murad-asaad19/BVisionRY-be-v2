package com.bvisionry.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;

/**
 * Architecture-freeze ("ratchet") tests for the module boundaries of the
 * {@code com.bvisionry} backend.
 *
 * <p>The codebase currently has no module-boundary enforcement: feature
 * packages import each other freely, several package pairs are cyclic, and
 * repositories are consumed across feature lines. A big-bang refactor is out of
 * scope. Instead these rules <strong>freeze the current violations</strong> in a
 * committed violation store (see {@code src/test/resources/archunit.properties}
 * and {@code src/test/resources/architecture/frozen-violations}). The build
 * fails on any <em>new</em> violation, while the frozen baseline can only be
 * reduced over time.
 *
 * <p>These are plain-JVM ArchUnit tests: no Spring context is started, so they
 * run inside the normal {@code mvn test} loop and stay fast.
 *
 * <p>Rules 4 and 5 are the "must pass outright" guards. Rule 4 (shared kernel
 * isolation) is genuinely clean today and is intentionally <em>not</em> frozen,
 * so it fails loudly the moment {@code common} reaches into a feature. Rule 5
 * (no field injection) has a handful of intentional exceptions in production
 * code (lazy self-injection for {@code @Async}/{@code @Cacheable} proxies and an
 * optional Redis template), so it is frozen to pin exactly those and forbid any
 * new field injection.
 */
@AnalyzeClasses(packages = "com.bvisionry", importOptions = DoNotIncludeTests.class)
class ArchitectureRulesTest {

    private static final String ROOT_PACKAGE = "com.bvisionry";
    private static final String ROOT_PREFIX = "com.bvisionry.";

    /**
     * Packages treated as the shared kernel / wiring layer. They are allowed to
     * reach across feature lines (e.g. to wire repositories) and are never the
     * "owner" of a cross-feature repository violation. Kept deliberately small
     * so real features cannot hide here.
     */
    private static final Set<String> SHARED_FEATURES = Set.of("common", "config");

    // ---------------------------------------------------------------------
    // Rule 1 (FROZEN): feature packages must be free of cycles.
    // Freezes the ~22 existing bidirectional package pairs; a NEW cycle fails.
    // ---------------------------------------------------------------------
    @ArchTest
    static final ArchRule featurePackagesShouldBeFreeOfCycles =
            freeze(slices().matching("com.bvisionry.(*)..")
                    .should().beFreeOfCycles());

    // ---------------------------------------------------------------------
    // Rule 2 (FROZEN): no cross-feature repository access.
    // A class in feature A must not depend on a *Repository owned by feature B.
    // Expressed as a custom condition that compares the first package segment
    // after com.bvisionry of the accessor vs the target repository, because a
    // per-feature "same feature" target set cannot be expressed as one fluent
    // slice rule. Robust and produces stable, freezable violation text.
    //
    // NOTE: this uses classes().should(condition), NOT noClasses(): the custom
    // condition already emits a `violated` event for each forbidden dependency,
    // and noClasses() would negate the condition (turning violations into
    // "satisfied" and reporting nothing). The .as() supplies the human-readable
    // (prohibitive) rule description used for the report and the freeze key.
    // ---------------------------------------------------------------------
    @ArchTest
    static final ArchRule noCrossFeatureRepositoryAccess =
            freeze(classes()
                    .should(dependOnRepositoriesOfAnotherFeature())
                    .as("no class in a feature package should depend on a *Repository owned by another feature"));

    // ---------------------------------------------------------------------
    // Rule 3 (FROZEN): @RestController classes are leaves — they may only be
    // depended on by classes in their own package. Freezes any existing fan-in.
    // ---------------------------------------------------------------------
    @ArchTest
    static final ArchRule restControllersShouldBeLeaves =
            freeze(classes()
                    .that().areAnnotatedWith(RestController.class)
                    .should(onlyBeDependedOnFromWithinTheirOwnPackage())
                    .as("@RestController classes should only be depended on by classes in their own package"));

    // ---------------------------------------------------------------------
    // Rule 4 (NOT FROZEN — must pass outright): shared-kernel isolation.
    // No class in com.bvisionry.common may depend on any com.bvisionry package
    // other than common itself (java/spring/library deps are ignored because
    // they are outside com.bvisionry). Verified clean today.
    // ---------------------------------------------------------------------
    @ArchTest
    static final ArchRule commonMustNotDependOnFeatures =
            noClasses()
                    .that().resideInAPackage("com.bvisionry.common..")
                    .should().dependOnClassesThat(
                            resideInAPackage("com.bvisionry..")
                                    .and(not(resideInAPackage("com.bvisionry.common..")))
                                    .as("reside in a com.bvisionry package other than common"))
                    .as("classes in com.bvisionry.common should not depend on any com.bvisionry feature package other than common");

    // ---------------------------------------------------------------------
    // Rule 5 (FROZEN — see class Javadoc): no field injection.
    // Constructor injection is the norm; the few intentional lazy self-injection
    // / optional-bean fields are pinned so no NEW field injection can appear.
    // ---------------------------------------------------------------------
    @ArchTest
    static final ArchRule noFieldInjection = freeze(NO_CLASSES_SHOULD_USE_FIELD_INJECTION);

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Returns the feature name of a class: the first package segment after
     * {@code com.bvisionry}. Returns {@code ""} for the application root class
     * and {@code null} for classes outside {@code com.bvisionry} (JDK, Spring,
     * third-party libraries), which are never in scope for these rules.
     */
    private static String featureOf(JavaClass javaClass) {
        String pkg = javaClass.getPackageName();
        if (pkg.equals(ROOT_PACKAGE)) {
            return "";
        }
        if (!pkg.startsWith(ROOT_PREFIX)) {
            return null;
        }
        String rest = pkg.substring(ROOT_PREFIX.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    /** A repository owned by this codebase: a {@code com.bvisionry} class whose simple name ends with {@code Repository}. */
    private static boolean isFeatureRepository(JavaClass javaClass) {
        String pkg = javaClass.getPackageName();
        return javaClass.getSimpleName().endsWith("Repository")
                && (pkg.equals(ROOT_PACKAGE) || pkg.startsWith(ROOT_PREFIX));
    }

    private static ArchCondition<JavaClass> dependOnRepositoriesOfAnotherFeature() {
        return new ArchCondition<>("depend on a *Repository owned by another feature") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String originFeature = featureOf(origin);
                // Classes outside com.bvisionry are never analysed here; the
                // shared kernel is allowed to wire any repository.
                if (originFeature == null || SHARED_FEATURES.contains(originFeature)) {
                    return;
                }
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    if (!isFeatureRepository(target)) {
                        continue;
                    }
                    String targetFeature = featureOf(target);
                    if (targetFeature == null || SHARED_FEATURES.contains(targetFeature)) {
                        continue;
                    }
                    if (!originFeature.equals(targetFeature)) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> onlyBeDependedOnFromWithinTheirOwnPackage() {
        return new ArchCondition<>("only be depended on by classes in their own package") {
            @Override
            public void check(JavaClass controller, ConditionEvents events) {
                String controllerPackage = controller.getPackageName();
                for (Dependency dependency : controller.getDirectDependenciesToSelf()) {
                    JavaClass origin = dependency.getOriginClass();
                    // Same package (including the controller's own nested
                    // classes) is fine; anything else means the controller is
                    // not a leaf.
                    if (origin.getPackageName().equals(controllerPackage)) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                }
            }
        };
    }
}
