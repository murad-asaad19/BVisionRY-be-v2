package com.bvisionry.comparison.web;

import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.comparison.dto.MyComparisonResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The member's own growth-comparison read (spec §5). Identity-scoped: the
 * authenticated caller IS the founder — no id in the path to widen.
 */
@RestController
@RequestMapping(path = "/api/my/growth", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Tag(name = "My Growth", description = "The member's distance comparison and trajectory.")
public class MyGrowthController {

    private final ComparisonQueryService queries;
    private final CurrentUserAccessor currentUser;

    @GetMapping("/comparison")
    public MyComparisonResponse comparison() {
        return queries.myComparison(currentUser.require().userId());
    }
}
