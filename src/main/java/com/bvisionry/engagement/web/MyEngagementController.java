package com.bvisionry.engagement.web;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.engagement.dto.EngagementRecordResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The signed-in founder's own engagement record — the participation table on
 * My Growth (operator decision 2026-08-19: a member may see their OWN
 * participation; spec §4's admin/coach-only rule still holds for everyone
 * else's).
 *
 * <p>Same stance as {@link MySessionController}: the path carries no id to
 * widen, so the only record reachable here is the caller's own, and the payload
 * ({@link EngagementRecordResponse}) is already per-member — no roster, no
 * other founder's attendance.
 */
@RestController
@RequestMapping(path = "/api/my/engagement", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Tag(name = "My engagement",
        description = "Participation score + attendance history for the signed-in founder.")
public class MyEngagementController {

    private final EngagementService service;
    private final CurrentUserAccessor currentUser;

    @GetMapping
    public EngagementRecordResponse mine() {
        CurrentUser me = currentUser.require();
        return service.myRecord(me.orgId(), me.userId());
    }
}
