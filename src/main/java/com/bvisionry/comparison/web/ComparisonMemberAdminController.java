package com.bvisionry.comparison.web;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.comparison.dto.MyComparisonResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Org-admin, member-anchored comparison read for the founder profile's Growth
 * tab: the SAME lifecycle payload ({@code done|pending|none} + trajectory) the
 * member and coach see, so the three surfaces can never disagree on the report
 * state. Same guard stack as {@link ComparisonAdminController}.
 */
@RestController
@RequestMapping(path = "/api/organizations/{orgId}/members/{userId}/growth-comparison",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@RequiredArgsConstructor
@Tag(name = "Distance comparison (admin)", description = "Member-anchored comparison for the founder profile.")
public class ComparisonMemberAdminController {

    private final ComparisonQueryService queries;

    @GetMapping
    public MyComparisonResponse memberGrowthComparison(@PathVariable UUID orgId,
            @PathVariable UUID userId) {
        return queries.memberComparisonForAdmin(orgId, userId);
    }
}
