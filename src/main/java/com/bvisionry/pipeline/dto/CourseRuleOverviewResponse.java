package com.bvisionry.pipeline.dto;

import java.util.UUID;

/**
 * One AI course rule as the platform Course-visibility screen lists it (spec
 * §2.5): pillar, the band that triggers it, the course, and the mode.
 *
 * <p>A flat cross-pipeline read of what {@code PillarCourseMappingService}
 * authors per pillar — the Pipeline Builder stays the place rules are WRITTEN;
 * this is the overview, and the one field it may flip is {@code mode}.
 *
 * @param threshold human copy for the band, pre-resolved ("50-69%"), or null
 *                  when the pillar's band set was shrunk under the rule.
 */
public record CourseRuleOverviewResponse(
        UUID id,
        UUID pipelineId,
        String pipelineName,
        UUID pillarId,
        String pillarName,
        int bandPosition,
        String bandLabel,
        String threshold,
        UUID courseId,
        String courseTitle,
        String courseState,
        String mode) {
}
