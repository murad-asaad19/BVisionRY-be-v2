package com.bvisionry.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a request handler that <strong>deliberately</strong> carries no method
 * security: its access rule is declared at the route layer in
 * {@code SecurityConfig} — either an explicit {@code permitAll()} matcher or the
 * {@code anyRequest().authenticated()} fallback — and is intentionally not
 * repeated here.
 *
 * <p>Authorization used to be opt-in per handler: a new {@code @GetMapping}
 * without {@code @PreAuthorize} compiled, passed the suite, and silently
 * inherited whichever route rule happened to match, with nothing recording
 * whether that was intended. The ArchUnit rule
 * {@code requestHandlersMustResolveToAnAuthorizationAnnotation} now requires
 * every handler to carry either {@code @PreAuthorize} or this marker, so being
 * open is a decision someone wrote down rather than a decision nobody made.
 *
 * <p>This annotation grants nothing, denies nothing and is read by nothing at
 * runtime. It records the reason at the call site, where a reviewer will see it.
 *
 * <p>The reason must name the actual route rule, because "public" and "any
 * signed-in user" are very different things and the name of this annotation does
 * not distinguish them.
 *
 * <h2>Enumerating the anonymous surface</h2>
 *
 * This marker is <strong>not</strong> the whole anonymous surface, and treating
 * it as such would under-count. A handler can also state "open" explicitly by
 * writing {@code @PreAuthorize("permitAll()")}, which is an equally valid
 * decision and which the ArchUnit rule accepts on its own terms. Both mechanisms
 * are legitimate; the invariant the rule actually guarantees is only that
 * <em>every</em> handler carries one of them or a real {@code @PreAuthorize}.
 *
 * <p>So the audit takes two greps, not one:
 *
 * <pre>
 *   grep -rn '@AuthorizedInSecurityConfig(' src/main/java
 *   grep -rn '@PreAuthorize("permitAll()")'  src/main/java
 * </pre>
 *
 * <p>The second one also matches CLASS-level uses (two controllers are public in
 * their entirety), so read its hits at class scope as well as method scope.
 */
// METHOD-only is load-bearing, not an oversight: a TYPE target would let one line
// bless an entire controller, silently pre-approving every handler added to it
// later — the exact failure the rule exists to kill. The rule refuses a
// class-level marker independently, so widening this cannot re-open the hole
// quietly, but do not widen it.
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuthorizedInSecurityConfig {

    /**
     * Which route rule governs this handler and why that is deliberate — e.g.
     * "permitAll: pre-auth entry point" or "authenticated(): any signed-in user".
     */
    String value();
}
