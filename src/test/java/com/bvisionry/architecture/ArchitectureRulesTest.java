package com.bvisionry.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
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
 * <p>Rule 3 (shared-kernel isolation) is genuinely clean today and is
 * intentionally <em>not</em> frozen, so it fails loudly the moment {@code common}
 * reaches into a feature. Rule 4 (no field injection) has a handful of
 * intentional exceptions in production code (lazy self-injection for
 * {@code @Async}/{@code @Cacheable} proxies and an optional Redis template), so
 * it is frozen to pin exactly those and forbid any new field injection.
 *
 * <p>Rule 6 (handler authorization) is likewise <em>not</em> frozen: its
 * intentional exceptions carry an {@code @AuthorizedInSecurityConfig} marker with
 * a written reason at the call site, which beats a baseline file for something a
 * reviewer needs to audit. See {@code RequestHandlerAuthorizationRuleTest} for
 * the falsification — a rule that has never been shown to fail is not evidence.
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
    // Rule 1 (FROZEN): no cross-feature dependencies.
    // Freezes every existing feature->feature type dependency; a NEW one fails,
    // so inter-feature coupling (and therefore any cycle) can only shrink.
    //
    // This deliberately replaces a slices().beFreeOfCycles() freeze: cycle
    // detection is capped (archunit cycles.maxNumberToDetect, default 100), so
    // with far more than 100 cycles present it enumerates a different truncated
    // SAMPLE each run and the frozen baseline never matches. Dependency EDGES are
    // uncapped and deterministic, freeze cleanly, and are a strictly stronger
    // ratchet (they catch new coupling even when it forms no cycle).
    // ---------------------------------------------------------------------
    @ArchTest
    static final ArchRule noCrossFeatureDependencies =
            freeze(classes()
                    .should(dependOnAnotherFeature())
                    .as("no class in a feature package should depend on another feature package"));

    // ---------------------------------------------------------------------
    // Rule 2 (FROZEN): @RestController classes are leaves — they may only be
    // depended on by classes in their own package. Freezes any existing fan-in.
    // ---------------------------------------------------------------------
    @ArchTest
    static final ArchRule restControllersShouldBeLeaves =
            freeze(classes()
                    .that().areAnnotatedWith(RestController.class)
                    .should(onlyBeDependedOnFromWithinTheirOwnPackage())
                    .as("@RestController classes should only be depended on by classes in their own package"));

    // ---------------------------------------------------------------------
    // Rule 3 (NOT FROZEN — must pass outright): shared-kernel isolation.
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
    // Rule 4 (FROZEN — see class Javadoc): no field injection.
    // Constructor injection is the norm; the few intentional lazy self-injection
    // / optional-bean fields are pinned so no NEW field injection can appear.
    // ---------------------------------------------------------------------
    @ArchTest
    static final ArchRule noFieldInjection = freeze(NO_CLASSES_SHOULD_USE_FIELD_INJECTION);

    // ---------------------------------------------------------------------
    // Rule 5 (FROZEN): fail-closed tenancy.
    // Multi-tenant isolation in this codebase is per-query discipline: services
    // load an aggregate by bare ID and then assert org ownership in a guard
    // helper (requireWorkshop, requireAssignmentInOrg, ...). Nothing structural
    // stops a NEW service method from calling repo.findById(id) and skipping
    // the assert — that is a silent cross-org leak.
    //
    // This rule makes the discipline structural: bare-ID Spring Data lookups
    // (findById / getById / getReferenceById / any findAll overload) on a
    // repository whose entity carries an org column (a field named orgId,
    // organizationId, or organization) may only be made from methods named
    // require* (incl. lambdas inside them). Every existing non-conforming call
    // site is pinned in the frozen store as a reviewed baseline; any NEW
    // unguarded bare-ID load fails the build.
    //
    // Scope notes: static analysis cannot prove the org assert itself — the
    // enforceable proxy is "route bare-ID loads through a require* guard"; the
    // guard body remains a review concern. Child entities without their own org
    // column (reached via already-guarded parents) and the Organization
    // aggregate itself are out of scope. Entity resolution reads the repo's
    // DIRECT parameterized interfaces (all repos here extend JpaRepository
    // directly); a repo inheriting its generics through an intermediate
    // interface would be missed — add handling if that pattern appears.
    // ---------------------------------------------------------------------
    @ArchTest
    static final ArchRule bareIdLoadsOnOrgOwnedReposRequireGuard =
            freeze(classes()
                    .should(onlyLoadOrgOwnedAggregatesThroughGuards())
                    .as("bare-ID repository loads on org-owned entities should only happen inside require* guard methods"));

    // ---------------------------------------------------------------------
    // Rule 6 (NOT FROZEN — must pass outright): authorization is opt-out, not
    // opt-in.
    //
    // Authorization here is method security: 102 @PreAuthorize annotations
    // across 75 controllers, backed by anyRequest().authenticated() in
    // SecurityConfig. Nothing structural required a handler to carry one — a new
    // @GetMapping with no annotation compiled, passed the suite, and silently
    // degraded to "any signed-in user", which is how LessonContentController
    // ended up as the single-layer outlier among its siblings.
    //
    // This rule makes the decision mandatory rather than the default: every
    // @*Mapping method on a @Controller/@RestController must resolve to
    // @PreAuthorize (on the method or on its declaring class, exactly as Spring
    // resolves it) or be explicitly marked
    // @AuthorizedInSecurityConfig("which route rule, and why that is deliberate").
    //
    // Deliberately NOT frozen. A frozen baseline would record "this handler was
    // bare on the day the rule landed" in a file nobody reads next to the code;
    // the marker records WHY at the call site, where a reviewer will see it. Note
    // the marker is NOT the whole anonymous surface — @PreAuthorize("permitAll()")
    // is the other legal spelling; see the annotation's javadoc for the two-grep
    // audit that actually enumerates it.
    //
    // Scope notes: like Rule 5, this is a structural proxy. It cannot judge
    // whether the expression is the RIGHT one — only that a decision was made.
    // @PreAuthorize("permitAll()") satisfies it, correctly: that IS an explicit
    // decision, just a different spelling of one.
    //
    // Known ceiling, verified unreachable rather than engineered around: the
    // rule evaluates a method on its DECLARING class, so a handler declared on
    // an abstract base class or an interface default method that is not itself
    // annotated @Controller would be invisible here, while Spring still honours
    // the inherited mapping on the concrete controller. Zero of the 75
    // controllers use extends or implements today, so the gap has no instances;
    // if that pattern ever appears, walk getAllRawSuperclasses/getAllRawInterfaces
    // in areRequestHandlers. (The meta-annotation handling above already closes
    // the composed-annotation half of the same class of evasion.)
    // ---------------------------------------------------------------------
    @ArchTest
    static final ArchRule requestHandlersMustResolveToAnAuthorizationAnnotation =
            methods()
                    .that(areRequestHandlers())
                    .should(resolveToAnAuthorizationAnnotation())
                    .as("every request handler should resolve to @PreAuthorize (on the method or its "
                            + "controller) or be explicitly marked @AuthorizedInSecurityConfig");

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

    private static ArchCondition<JavaClass> dependOnAnotherFeature() {
        return new ArchCondition<>("depend on another feature package") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String originFeature = featureOf(origin);
                // Only real feature classes are constrained; the shared kernel
                // (common/config) is the wiring layer and may cross features.
                if (originFeature == null || originFeature.isEmpty()
                        || SHARED_FEATURES.contains(originFeature)) {
                    return;
                }
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    String targetFeature = featureOf(dependency.getTargetClass());
                    // Ignore JDK/library targets (null), the app root (""), the
                    // shared kernel, and same-feature dependencies.
                    if (targetFeature == null || targetFeature.isEmpty()
                            || SHARED_FEATURES.contains(targetFeature)
                            || originFeature.equals(targetFeature)) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                }
            }
        };
    }

    /** Field names that mark an entity as org-owned (all three shapes in use). */
    private static final Set<String> ORG_FIELD_NAMES = Set.of("orgId", "organizationId", "organization");

    /** Spring Data methods that load rows with no tenant predicate in the query. */
    private static final Set<String> BARE_ID_METHODS = Set.of("findById", "getById", "getReferenceById", "findAll");

    private static ArchCondition<JavaClass> onlyLoadOrgOwnedAggregatesThroughGuards() {
        return new ArchCondition<>("only load org-owned aggregates through require* guard methods") {
            private final Set<String> orgOwnedRepositories = new HashSet<>();

            @Override
            public void init(Collection<JavaClass> allClasses) {
                orgOwnedRepositories.clear();
                Set<String> orgOwnedEntities = new HashSet<>();
                for (JavaClass clazz : allClasses) {
                    if (clazz.isAnnotatedWith("jakarta.persistence.Entity")
                            && clazz.getAllFields().stream().anyMatch(f -> ORG_FIELD_NAMES.contains(f.getName()))) {
                        orgOwnedEntities.add(clazz.getName());
                    }
                }
                for (JavaClass clazz : allClasses) {
                    if (clazz.isInterface()
                            && clazz.isAssignableTo("org.springframework.data.repository.Repository")
                            && orgOwnedEntities.contains(repositoryEntityName(clazz))) {
                        orgOwnedRepositories.add(clazz.getName());
                    }
                }
            }

            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                for (JavaMethodCall call : origin.getMethodCallsFromSelf()) {
                    if (!orgOwnedRepositories.contains(call.getTargetOwner().getName())
                            || !BARE_ID_METHODS.contains(call.getTarget().getName())) {
                        continue;
                    }
                    String caller = call.getOrigin().getName();
                    if (caller.startsWith("require") || caller.startsWith("lambda$require")) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.violated(call, call.getDescription()));
                }
            }
        };
    }

    /**
     * Resolves the entity type a Spring Data repository manages by reading the
     * first type argument of its direct parameterized {@code Repository}
     * super-interface (e.g. {@code JpaRepository<Workshop, UUID>} → Workshop).
     * Returns {@code null} when no such interface is present.
     */
    private static String repositoryEntityName(JavaClass repository) {
        for (JavaType iface : repository.getInterfaces()) {
            if (!(iface instanceof JavaParameterizedType parameterized)) {
                continue;
            }
            if (!iface.toErasure().isAssignableTo("org.springframework.data.repository.Repository")) {
                continue;
            }
            var typeArguments = parameterized.getActualTypeArguments();
            if (!typeArguments.isEmpty()) {
                return typeArguments.get(0).toErasure().getName();
            }
        }
        return null;
    }

    /** Spring's request-mapping annotations — the full set that creates a handler. */
    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.PatchMapping",
            "org.springframework.web.bind.annotation.DeleteMapping");

    private static final String PRE_AUTHORIZE = "org.springframework.security.access.prepost.PreAuthorize";
    private static final String ROUTE_LAYER_MARKER = "com.bvisionry.common.security.AuthorizedInSecurityConfig";
    private static final String CONTROLLER = "org.springframework.stereotype.Controller";

    /** True when {@code element} carries {@code annotation} directly or via a composed annotation. */
    private static boolean carries(CanBeAnnotated element, String annotation) {
        return element.isAnnotatedWith(annotation) || element.isMetaAnnotatedWith(annotation);
    }

    /**
     * A method Spring will dispatch a request to: a {@code @*Mapping} method on
     * a class annotated with {@code @Controller} or with anything meta-annotated
     * by it (which is how {@code @RestController} is defined).
     *
     * <p>Both halves are meta-aware. Spring resolves composed annotations on
     * either side, so a custom {@code @AdminGetMapping} meta-annotated with
     * {@code @GetMapping} still creates a route — and a direct-only check here
     * would let it slip past the rule entirely.
     */
    private static DescribedPredicate<JavaMethod> areRequestHandlers() {
        return DescribedPredicate.describe("are Spring request handlers", method ->
                carries(method.getOwner(), CONTROLLER)
                        && MAPPING_ANNOTATIONS.stream().anyMatch(a -> carries(method, a)));
    }

    /**
     * The two branches are deliberately asymmetric. {@code @PreAuthorize} counts
     * on the method OR on the controller, because that is exactly how Spring
     * resolves it. The route-layer marker counts on the METHOD ONLY: blessing a
     * whole controller in one line — so that every handler added to it later is
     * silently pre-approved — is the precise failure this rule exists to kill.
     * {@code @AuthorizedInSecurityConfig} is {@code @Target(METHOD)} today, so
     * this branch is currently unreachable; it is written this way so widening
     * that target can never quietly re-open the hole.
     */
    private static ArchCondition<JavaMethod> resolveToAnAuthorizationAnnotation() {
        return new ArchCondition<>("resolve to an authorization annotation") {
            @Override
            public void check(JavaMethod handler, ConditionEvents events) {
                if (carries(handler, PRE_AUTHORIZE)
                        || carries(handler, ROUTE_LAYER_MARKER)
                        || carries(handler.getOwner(), PRE_AUTHORIZE)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(handler, String.format(
                        "handler %s has no @PreAuthorize on the method or on %s and is not marked "
                                + "@AuthorizedInSecurityConfig: it would silently inherit whichever "
                                + "SecurityConfig route rule happens to match (the fallback is merely "
                                + "authenticated()). Add the @PreAuthorize it needs, or "
                                + "@AuthorizedInSecurityConfig(\"which route rule, and why that is "
                                + "deliberate\") if the route layer really is the whole story, in %s",
                        handler.getFullName(),
                        handler.getOwner().getSimpleName(),
                        handler.getSourceCodeLocation())));
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
