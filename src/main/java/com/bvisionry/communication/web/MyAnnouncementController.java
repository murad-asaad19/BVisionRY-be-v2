package com.bvisionry.communication.web;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.communication.dto.MyAnnouncementResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The RECIPIENT side of cohort announcements: what reached the signed-in
 * member. Identity-scoped — the caller IS the audience, so there is no org or
 * cohort path parameter to widen: the service resolves the caller's own org +
 * cohort memberships in SQL and nothing else is reachable.
 */
@RestController
@RequestMapping(path = "/api/my/announcements", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Tag(name = "My announcements", description = "Announcements the signed-in member received.")
public class MyAnnouncementController {

    private final AnnouncementService service;

    @GetMapping
    public List<MyAnnouncementResponse> myAnnouncements() {
        return service.myFeed();
    }
}
