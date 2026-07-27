package com.bvisionry.coaching.web;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.coaching.dto.FounderCoachSummary;
import com.bvisionry.coaching.repository.CoachingReadRepository;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The founder's side of the coaching relationship — the first surface in the
 * app that tells a founder who their coach is, and the booking link that comes
 * with it (roadmap §7 / policy {@code calendar: INTEGRATE_CAL_COM}).
 *
 * <p>Three-layer defense, same shape as the coach console: {@code SecurityConfig}'s
 * {@code anyRequest().authenticated()} floor, {@code @PreAuthorize} here, and a
 * query that carries the caller's own id into the shared assignment-union
 * relation. The caller's identity IS the scope — the path has no id to widen,
 * so the only answer this endpoint can give is "the coaches who may see YOU".
 * A caller who is not a visible MEMBER (an admin, an instructor, an org-less
 * account) gets an empty list from the SQL, not a 403 from a role check: the
 * relation, not the role name, is the authority.
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
@Tag(name = "My coaches", description = "The signed-in founder's assigned coaches and their booking links.")
public class FounderCoachController {

    private final CoachingReadRepository reads;
    private final CurrentUserAccessor currentUser;

    /**
     * An org-less caller needs no short-circuit: a null {@code :orgId} binds
     * fine (Postgres infers the parameter type from the comparison) and the
     * tenant equality is then never true, so the query returns nothing. The
     * early return this used to carry claimed to prevent a driver error that
     * does not happen — pinned instead by
     * {@code anOrgLessAccountGetsAnEmptyListNotAnError}, which now exercises
     * the real SQL path.
     */
    @GetMapping
    public List<FounderCoachSummary> myCoaches() {
        CurrentUser caller = currentUser.require();
        return reads.coachesOfMember(caller.orgId(), caller.userId()).stream()
                .map(r -> new FounderCoachSummary(r.id(), r.name(), r.bookingUrl()))
                .toList();
    }
}
