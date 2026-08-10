package com.bvisionry.courseaccess.web;

import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.courseaccess.dto.AssignCourseRequest;
import com.bvisionry.courseaccess.dto.CatalogCourseView;
import com.bvisionry.courseaccess.dto.MemberCourseView;
import com.bvisionry.courseaccess.dto.OrgCourseRow;
import com.bvisionry.courseaccess.dto.UpdateOrgCourseRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The org admin's Courses tab (spec §2.3).
 *
 * <p>Same guard stack as every other org console: {@code OrgAccessInterceptor}
 * on the {@code /api/organizations/{orgId}/**} pattern plus the class
 * {@code @PreAuthorize}. Methods deliberately carry no {@code @PreAuthorize} of
 * their own — Spring REPLACES rather than ANDs, so a method annotation here
 * would delete the class-level tenancy gate.
 */
@RestController
@RequestMapping(path = "/api/organizations/{orgId}/courses", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@RequiredArgsConstructor
@Tag(name = "Courses (org admin)", description = "Course assignment, override and completion for one organization.")
public class OrgCourseController {

    private final OrgCourseService service;
    private final CurrentUserAccessor currentUser;

    @GetMapping
    public List<OrgCourseRow> list(@PathVariable UUID orgId) {
        return service.list(orgId);
    }

    /** The courses this org may assign from — PUBLISHED and visible to it (spec §3). */
    @GetMapping("/assignable")
    public List<CatalogCourseView> assignable(@PathVariable UUID orgId) {
        return service.assignable(orgId);
    }

    /** The effective course list of one member — the Work tab and the assign dialog's badges. */
    @GetMapping("/members/{memberId}")
    public List<MemberCourseView> memberCourses(@PathVariable UUID orgId, @PathVariable UUID memberId) {
        return service.coursesOfMember(orgId, memberId);
    }

    @PostMapping
    public ResponseEntity<Void> assign(@PathVariable UUID orgId,
                                       @Valid @RequestBody AssignCourseRequest request) {
        service.assign(orgId, request, currentUser.require().userId());
        return ResponseEntity.noContent().build();
    }

    /** Spec §11: convert to optional / required (and re-date) after assignment. */
    @PatchMapping("/{courseId}")
    public ResponseEntity<Void> update(@PathVariable UUID orgId, @PathVariable UUID courseId,
                                       @Valid @RequestBody UpdateOrgCourseRequest request) {
        service.update(orgId, courseId, request);
        return ResponseEntity.noContent().build();
    }

    /** "Remove for everyone" — delete the rule, or cancel every enrollment of that source. */
    @DeleteMapping("/{courseId}")
    public ResponseEntity<Void> removeForEveryone(@PathVariable UUID orgId, @PathVariable UUID courseId,
                                                  @RequestParam String source) {
        service.removeForEveryone(orgId, courseId, source);
        return ResponseEntity.noContent().build();
    }

    /** Per-member exception (spec §2.4) — writes the exclusion row AND cancels any enrollment. */
    @DeleteMapping("/{courseId}/members/{memberId}")
    public ResponseEntity<Void> removeForMember(@PathVariable UUID orgId, @PathVariable UUID courseId,
                                                @PathVariable UUID memberId,
                                                @RequestParam(required = false) String reason) {
        service.removeForMember(orgId, memberId, courseId, reason, currentUser.require().userId());
        return ResponseEntity.noContent().build();
    }
}
