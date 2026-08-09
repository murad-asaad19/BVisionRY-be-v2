package com.bvisionry.comparison.web;

import com.bvisionry.common.coachaccess.CoachAccess;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.comparison.dto.MyComparisonResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The coach's read of a founder's growth comparison — same payload the
 * founder sees, gated by the shared {@link CoachAccess} assignment-union
 * predicate (a founder outside the union is a 404, exactly as in the coach
 * console). Route floor {@code /api/v1/coach/**} requires COACH in
 * SecurityConfig; the class re-asserts it.
 */
@RestController
@RequestMapping(path = "/api/v1/coach/founders/{founderId}/comparison",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('COACH')")
@RequiredArgsConstructor
@Tag(name = "Distance comparison (coach)", description = "A founder's comparison for their coach.")
public class ComparisonCoachController {

    private final ComparisonQueryService queries;
    private final CoachAccess coachAccess;
    private final CurrentUserAccessor currentUser;

    @GetMapping
    public MyComparisonResponse comparison(@PathVariable UUID founderId) {
        CurrentUser coach = currentUser.require();
        if (!coachAccess.coachSees(coach.orgId(), coach.userId(), founderId)) {
            throw new ResourceNotFoundException("Founder", founderId.toString());
        }
        return queries.founderComparisonForCoach(founderId);
    }
}
