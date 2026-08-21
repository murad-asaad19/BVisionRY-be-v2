package com.bvisionry.communication.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The recipient side: flagging a post. Self-scoped like {@code /api/notifications}
 * — a recipient knows the announcement id and nothing else, so there is no org
 * or cohort path segment to widen.
 *
 * <p>Three-layer defense: the route pattern requires authentication (the only
 * announcement route open to a MEMBER), {@code @PreAuthorize} re-asserts it,
 * and the service refuses any announcement whose cohort the caller is not
 * enrolled in — a post you never received is a 404, so this cannot be used to
 * probe for ids.
 */
@RestController
@RequestMapping("/api/v1/announcements")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Tag(name = "Announcements", description = "Cohort broadcasts by an org admin or an assigned coach.")
public class AnnouncementReportController {

    private final AnnouncementService service;

    @PostMapping("/{announcementId}/report")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@PathVariable UUID announcementId) {
        service.report(announcementId);
    }
}
