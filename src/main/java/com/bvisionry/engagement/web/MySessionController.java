package com.bvisionry.engagement.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.engagement.domain.SessionType;
import com.bvisionry.engagement.repository.EngagementReadRepository;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * "Bvisionry Labs" — the live sessions an admin or coach has already scheduled
 * for the cohorts the signed-in founder belongs to.
 *
 * <p>Spec §4 keeps the Engagement Record and the participation score off the
 * member's report. This is not that: it answers "when are we meeting, and did I
 * make it", carrying no roster, no other founder's attendance and no marker
 * names. The distinction is enforced by the query
 * ({@link EngagementReadRepository#mySessions}), not by this class trimming a
 * wider row afterwards — a response that never holds another member's data
 * cannot leak it.
 *
 * <p>Same three-layer stance as {@code FounderCoachController}: the security
 * floor, {@code @PreAuthorize} here, and a query anchored on the caller's own
 * id. The path carries no id to widen, so the only answer available is the
 * caller's own schedule; a caller enrolled in nothing gets an empty list rather
 * than a 403.
 *
 * <p>No service class — there is nothing to orchestrate between the read and
 * the response.
 */
@RestController
@RequestMapping(path = "/api/my/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Tag(name = "My sessions",
        description = "Live sessions scheduled for the signed-in founder's cohorts.")
public class MySessionController {

    private final EngagementReadRepository reads;
    private final CurrentUserAccessor currentUser;

    /**
     * @param attended whether the caller was ticked present. Meaningless for a
     *                 session still in the future, which no one has marked yet.
     */
    public record MySessionDto(
            UUID id,
            UUID cohortId,
            String cohortName,
            SessionType type,
            String title,
            Instant sessionDate,
            boolean attended) {
    }

    /** Newest first — the same order the admin Sessions tab reads in. */
    @GetMapping
    public List<MySessionDto> mine() {
        return reads.mySessions(currentUser.require().userId()).stream()
                .map(r -> new MySessionDto(r.id(), r.cohortId(), r.cohortName(),
                        SessionType.valueOf(r.type()), r.title(), r.sessionDate(),
                        r.attended()))
                .toList();
    }
}
