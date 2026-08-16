package com.bvisionry.coaching.web;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.coaching.dto.FounderCoachSummary;
import com.bvisionry.coaching.repository.CoachingReadRepository;
import com.bvisionry.coaching.repository.CoachingReadRepository.CoachOfMemberRow;
import com.bvisionry.common.media.MediaUrlPort;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The founder's side of the coaching relationship — who their coach is, who
 * else coaches in their org ("Coaches Corner"), and the booking link that comes
 * with each (roadmap §7 / policy {@code calendar: INTEGRATE_CAL_COM}).
 *
 * <p>Three-layer defense, same shape as the coach console: {@code SecurityConfig}'s
 * {@code anyRequest().authenticated()} floor, {@code @PreAuthorize} here, and a
 * query that carries the caller's own id into the shared assignment-union
 * relation. The caller's identity IS the scope — the path has no id to widen,
 * so the only answers this endpoint can give are "the coaches who may see YOU"
 * and "the coaches in YOUR org". A caller who is not a visible MEMBER (an admin,
 * an instructor, an org-less account) gets an empty default list from the SQL,
 * not a 403 from a role check: the relation, not the role name, is the
 * authority.
 *
 * <p>No service class: there is nothing to orchestrate between the read and the
 * response, and a pass-through service would just be a second place to keep in
 * sync. The write side lives on {@link CoachConsoleController}/
 * {@code CoachConsoleService}, which does have state to manage.
 */
@RestController
@RequestMapping(path = "/api/v1/me/coaches", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Tag(name = "My coaches", description = "The signed-in founder's coaches and their booking links.")
public class FounderCoachController {

    /** The one widening value {@code ?scope=} accepts; anything else is the default. */
    private static final String SCOPE_ALL = "all";

    private final CoachingReadRepository reads;
    private final CurrentUserAccessor currentUser;
    private final MediaUrlPort mediaUrlPort;

    /**
     * An org-less caller needs no short-circuit: a null {@code :orgId} binds
     * fine (Postgres infers the parameter type from the comparison) and the
     * tenant equality is then never true, so the query returns nothing. The
     * early return this used to carry claimed to prevent a driver error that
     * does not happen — pinned instead by
     * {@code anOrgLessAccountGetsAnEmptyListNotAnError}, which now exercises
     * the real SQL path.
     *
     * @param scope absent (the default) = only the coaches assigned to the
     *              caller, exactly as before Coaches Corner existed;
     *              {@code all} = every ACTIVE COACH of the caller's own org, so
     *              a founder can meet the bench before they are assigned to it.
     *              An UNRECOGNISED value falls back to the DEFAULT rather than
     *              400ing: the fallback direction is the narrow one, so a typo
     *              can only ever show a founder less than they asked for.
     */
    @GetMapping
    public List<FounderCoachSummary> myCoaches(
            @Parameter(description = "`all` lists every active coach in the caller's org; "
                    + "absent lists only the coaches assigned to the caller.")
            @RequestParam(required = false) String scope) {
        CurrentUser caller = currentUser.require();
        List<CoachOfMemberRow> rows = SCOPE_ALL.equalsIgnoreCase(scope)
                ? reads.coachesInOrg(caller.orgId())
                : reads.coachesOfMember(caller.orgId(), caller.userId());
        return rows.stream()
                // The photo leaves the column as a `minio://` marker; a browser
                // cannot load one, so the port resolves it to a short-lived
                // presigned GET here. Anything else (external URL, null) passes
                // through untouched.
                .map(r -> new FounderCoachSummary(r.id(), r.name(), r.headline(), r.bio(),
                        mediaUrlPort.resolveUrl(r.photoUrl()), r.bookingUrl()))
                .toList();
    }
}
