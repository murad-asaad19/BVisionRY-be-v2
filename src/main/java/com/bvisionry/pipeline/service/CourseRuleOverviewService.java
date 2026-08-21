package com.bvisionry.pipeline.service;

import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.pipeline.dto.CourseRuleOverviewResponse;
import com.bvisionry.pipeline.dto.UpdateCourseRuleModeRequest;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.PillarCourseMapping;
import com.bvisionry.pipeline.repository.CourseCatalogReadRepository;
import com.bvisionry.pipeline.repository.CourseCatalogReadRepository.CourseRef;
import com.bvisionry.pipeline.repository.PillarCourseMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The platform's cross-pipeline view of the AI course rules (spec §2.5, the
 * Course-visibility screen's second table).
 *
 * <p>Read-mostly on purpose: rules are AUTHORED per pillar in the Pipeline
 * Builder, where the band set that gives {@code bandPosition} its meaning lives.
 * The one thing this surface may change is {@code mode}, because Suggest vs
 * Auto-assign is a platform posture question ("do we put founders on courses, or
 * offer them?") rather than a per-pillar authoring detail.
 */
@Service
@RequiredArgsConstructor
public class CourseRuleOverviewService {

    private final PillarCourseMappingRepository mappings;
    private final CourseCatalogReadRepository courseCatalog;

    @Transactional(readOnly = true)
    public List<CourseRuleOverviewResponse> list() {
        List<PillarCourseMapping> rules = mappings.findAllWithPillar();
        Map<UUID, CourseRef> courses = courseCatalog.findByIdsUnscoped(
                rules.stream().map(PillarCourseMapping::getCourseId).collect(Collectors.toSet()));

        return rules.stream().map(rule -> {
            Pillar pillar = rule.getPillar();
            List<PillarBands.Band> bands = PillarBands.ordered(pillar);
            // Null when the pillar's band set was shrunk under the rule — the row
            // is inert, and is reported rather than hidden (same contract as
            // PillarCourseMappingResponse).
            PillarBands.Band band = rule.getBandPosition() < bands.size()
                    ? bands.get(rule.getBandPosition())
                    : null;
            CourseRef course = courses.get(rule.getCourseId());
            return new CourseRuleOverviewResponse(
                    rule.getId(),
                    pillar.getPipeline().getId(),
                    pillar.getPipeline().getName(),
                    pillar.getId(),
                    pillar.getName(),
                    rule.getBandPosition(),
                    band == null ? null : band.label(),
                    band == null ? null : "%d-%d%%".formatted(band.min(), band.max()),
                    rule.getCourseId(),
                    course == null ? null : course.title(),
                    course == null ? null : course.state(),
                    rule.getMode().name());
        }).toList();
    }

    @Transactional
    public void setMode(UUID ruleId, UpdateCourseRuleModeRequest request) {
        PillarCourseMapping rule = mappings.findById(ruleId)
                .orElseThrow(() -> new ResourceNotFoundException("Course rule", ruleId.toString()));
        rule.setMode(PillarCourseMappingService.parseMode(request.mode()));
        mappings.save(rule);
    }
}
