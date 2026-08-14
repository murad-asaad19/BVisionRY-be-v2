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
 * <p>Orthogonal to entitlement, exactly as {@link PremiumFeatureGuard} is, and
 * deliberately NARROWER than tenancy: it asks only "which ROLE may put real
 * names in a file", and leaves "whose names" entirely to the class-level
 * {@code @PreAuthorize} that every calling controller already carries. A COACH
 * of a PAYING org clears entitlement and tenancy on a founder they are assigned
 * and is still refused here.
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
 * it should not.</b> Operator ruling, 2026-08-14 (superseding the narrower
 * 2026-07-27 ruling, which allowed an org admin to SEE their founders' names on
 * screen but still refused them a file): <b>an ORG_ADMIN may generate export
 * files carrying their own org's members' real names.</b> They administer a
 * cohort they are accountable for and already correspond with those people by
 * email, and the file adds nothing they cannot already read off
 * {@code /dashboard/overview}. So the bullets above are not a backlog; they are
 * the intended product, and nothing here should be "hardened" into hiding names
 * from an in-org admin.
 *
 * <p>What the guard still buys, post-ruling, is the COACH door and the
 * unauthenticated one: a coach sees assigned founders across a boundary the org
 * admin does not own, and a file outlives the session, so a coach's export stays
 * masked unconditionally. {@code SUPER_ADMIN or ORG_ADMIN} is therefore the
 * whole allowlist; everything else — COACH, MEMBER, anonymous, absent — is
 * denied.
 *
 * <h2>Why a guard called imperatively, and not {@code @PreAuthorize}</h2>
 *
 * Every org-scoped export surface already carries a CLASS-level
 * {@code @PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN')
 * and @orgAccess.isInOrg(#orgId))")}. That is what makes the ROLE-ONLY test
 * below sufficient and correct: <b>an ORG_ADMIN who reaches one of these
 * handlers has already been pinned to their own {@code orgId} by that class
 * expression</b>, so "is an org admin" here can only ever mean "is an org admin
 * of THIS org". The guard never re-checks tenancy because it never has to, and
 * duplicating the check would be a second copy to drift.
 *
 * <p>It also cannot become a method-level annotation. Spring method security
 * <em>replaces</em> a class-level expression with a method-level one — it never
 * ANDs them — so adding {@code @PreAuthorize("!#showNames or ...")} to a handler
 * would silently DELETE that class-level tenancy gate and turn a name-masking
 * bug into a cross-tenant one. Restating the class expression eight times to
 * avoid that is eight chances to mistype it.
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
     * SUPER_ADMIN or an ORG_ADMIN. A no-op when {@code showNames} is false, so it
     * is safe (and intended) as the unconditional first line of every export
     * handler that takes the flag.
     *
     * <p>The ORG_ADMIN case is safe WITHOUT a tenancy check here because every
     * caller of this method is a handler whose class-level {@code @PreAuthorize}
     * has already required {@code @orgAccess.isInOrg(#orgId)} of an org admin —
     * see the class javadoc.
     *
     * @param showNames the request's {@code showNames} flag, as received
     * @throws AccessDeniedException when {@code showNames} is true and the caller
     *                               is neither a SUPER_ADMIN nor an ORG_ADMIN —
     *                               notably a COACH, and including when nobody is
     *                               authenticated at all
     */
    public static void checkShowNames(boolean showNames) {
        if (!showNames) {
            return;
        }
        if (!hasAnyAuthority(UserRole.SUPER_ADMIN, UserRole.ORG_ADMIN)) {
            throw new AccessDeniedException(
                    "Only a super admin or an org admin may export unmasked member names");
        }
    }

    /**
     * Reads the granted authorities, which is the same test the class-level
     * {@code @PreAuthorize} on every one of these controllers already makes —
     * so a caller who reached the handler has necessarily been measured by the
     * same yardstick. Both JWT filters build the authority list as
     * {@code List.of(new SimpleGrantedAuthority(user.getRole().name()))}, so it
     * cannot diverge in production from the principal's role.
     *
     * <p>An absent or anonymous {@link Authentication} matches no role and is
     * therefore denied — the fail-closed answer.
     */
    private static boolean hasAnyAuthority(UserRole... allowed) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            for (UserRole role : allowed) {
                if (role.name().equals(authority.getAuthority())) {
                    return true;
                }
            }
        }
        return false;
    }
}
