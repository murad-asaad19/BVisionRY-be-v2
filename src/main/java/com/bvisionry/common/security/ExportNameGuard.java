package com.bvisionry.common.security;

import com.bvisionry.common.enums.UserRole;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Authority gate for the {@code showNames} export flag: "may this caller take a
 * copy of this org's data with the founders' real names on it".
 *
 * <p>Orthogonal to tenancy, RBAC and entitlement, exactly as
 * {@link PremiumFeatureGuard} is: an ORG_ADMIN of a PREMIUM org clears all three
 * on their own org's exports and is still refused here.
 *
 * <h2>What this is NOT — read before citing this class</h2>
 *
 * This is <b>document hygiene, not an anonymity boundary</b>, and no comment in
 * this codebase should say otherwise. In-org founder anonymity is <b>not a
 * recorded product promise</b> — it appears in no pricing copy, no roadmap
 * clause and no policy decision (the only anonymity rule,
 * {@code benchmark_anonymity: AGGREGATE_ONLY}, is about CROSS-org benchmarks) —
 * and the system does not keep it:
 *
 * <ul>
 *   <li>{@code GET /api/organizations/{orgId}/dashboard/overview} returns
 *       {@code memberName} AND {@code memberEmail} for every member, as JSON, to
 *       every in-org ORG_ADMIN.</li>
 *   <li>The masked labels are REVERSIBLE. {@code OrgInsight{Excel,Pdf}Service}
 *       orders {@code Member 1..N} by {@code user.id} on purpose, to keep the
 *       mapping stable across the AI prompt and both exports. Sorting the
 *       overview by {@code userId} re-identifies every row.</li>
 *   <li>Workshop {@code /analytics}, {@code /live}, {@code /teams} and
 *       {@code /members/{userId}/answers} all return real names unguarded.</li>
 * </ul>
 *
 * So what this guard buys is that an UNMASKED FILE is not generated and
 * circulated — a real and worthwhile control, and a much narrower one than
 * "founders are anonymous to their org admin".
 *
 * <p><b>Whether the latter should be true was escalated and has been DECIDED:
 * it should not.</b> Operator ruling, 2026-07-27 — an org admin may see their
 * own founders' names, because they administer a cohort they are accountable
 * for and already correspond with those people by email. So the bullets above
 * are not a backlog; they are the intended product, and nothing here should be
 * "hardened" into hiding names from an in-org admin. This guard survives the
 * ruling for a different and narrower reason: a FILE leaves the building, gets
 * forwarded and outlives the session, so who may generate one is a separate
 * question from who may see a name on screen.
 *
 * <h2>Why a guard called imperatively, and not {@code @PreAuthorize}</h2>
 *
 * Every org-scoped export surface already carries a CLASS-level
 * {@code @PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN')
 * and @orgAccess.isInOrg(#orgId))")}. Spring method security <em>replaces</em> a
 * class-level expression with a method-level one — it never ANDs them — so
 * adding {@code @PreAuthorize("!#showNames or hasAuthority('SUPER_ADMIN')")} to a
 * handler would silently DELETE that class-level tenancy gate and turn a
 * name-masking bug into a cross-tenant one. Restating the class expression eight
 * times to avoid that is eight chances to mistype it.
 *
 * <h2>Why not in the services</h2>
 *
 * The services provably lack the context. {@code MemberDisplayNameResolver}
 * serves BOTH the org-admin per-member export and {@code /api/my/...}, where the
 * caller is the submission's own owner and {@code showNames=true} is legitimate;
 * a guard there would break the member's own report. Only the handler knows
 * which surface it is. There are four independent name-resolution paths behind
 * these handlers, so "one guard in the resolver" would have covered a third of
 * them and broken the self-export.
 *
 * <h2>Deny, never silently mask</h2>
 *
 * A silent downgrade to {@code showNames=false} would permanently hide both the
 * client bug that sent {@code true} and any future regression that stops
 * masking. {@link AccessDeniedException} surfaces as a 403 through the existing
 * handler in {@code GlobalExceptionHandler}, which already logs it — no new
 * logging machinery.
 *
 * <h2>Why static rather than an injected bean</h2>
 *
 * {@link PremiumFeatureGuard} is a bean because it has two collaborators to
 * inject. This has none: the question is answered entirely from the
 * {@link SecurityContextHolder}, the same way {@code OrgAccessGuard} answers
 * its own. Making it a bean would buy nothing but a constructor parameter on
 * every export controller — and one of those constructors is pinned, signature
 * and all, in the committed ArchUnit frozen-violation store, which is
 * append-only by policy.
 */
public final class ExportNameGuard {

    private ExportNameGuard() {
    }

    /**
     * Refuses a request that asked for unmasked names unless the caller is a
     * SUPER_ADMIN. A no-op when {@code showNames} is false, so it is safe (and
     * intended) as the unconditional first line of every export handler that
     * takes the flag.
     *
     * @param showNames the request's {@code showNames} flag, as received
     * @throws AccessDeniedException when {@code showNames} is true and the caller
     *                               is not a SUPER_ADMIN — including when nobody
     *                               is authenticated at all
     */
    public static void checkShowNames(boolean showNames) {
        if (!showNames) {
            return;
        }
        if (!isSuperAdmin()) {
            throw new AccessDeniedException("Only a super admin may export unmasked member names");
        }
    }

    /**
     * Reads the granted authority, which is the same test the class-level
     * {@code @PreAuthorize} on every one of these controllers already makes —
     * so a caller who reached the handler has necessarily been measured by the
     * same yardstick. Both JWT filters build the authority list as
     * {@code List.of(new SimpleGrantedAuthority(user.getRole().name()))}, so it
     * cannot diverge in production from the principal's role.
     *
     * <p>An absent or anonymous {@link Authentication} reads as "not a super
     * admin" and is therefore denied — the fail-closed answer.
     */
    private static boolean isSuperAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (UserRole.SUPER_ADMIN.name().equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
