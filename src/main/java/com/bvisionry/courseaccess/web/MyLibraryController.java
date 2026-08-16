package com.bvisionry.courseaccess.web;

import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.courseaccess.dto.MemberCourseView;
import com.bvisionry.courseaccess.dto.MyLibraryResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The member Library (spec §2.1) and its one-tap Accept.
 *
 * <p>Tenant scoping is by construction: the member id comes from
 * {@link CurrentUserAccessor}, never from a path or a body, and it constrains
 * every read. There is no argument a caller can pass that returns or mutates
 * someone else's shelf.
 */
@RestController
@RequestMapping(path = "/api/my/library", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Tag(name = "Library (member)", description = "The member's courses and the catalog their org may browse.")
public class MyLibraryController {

    private final CourseAccessService service;
    private final CurrentUserAccessor currentUser;

    @GetMapping
    public MyLibraryResponse library() {
        CurrentUser me = currentUser.require();
        return service.library(me.userId(), me.orgId());
    }

    /**
     * Accept an AI suggestion, or take up an org-rule course for the first time.
     * Refused for anything the caller does not already have effectively.
     */
    @PostMapping("/courses/{courseId}/accept")
    public MemberCourseView accept(@PathVariable UUID courseId) {
        CurrentUser me = currentUser.require();
        return service.accept(me.userId(), me.orgId(), courseId);
    }
}
