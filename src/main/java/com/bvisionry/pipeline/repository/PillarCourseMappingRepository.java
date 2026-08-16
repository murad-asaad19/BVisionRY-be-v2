package com.bvisionry.pipeline.repository;

import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.PillarCourseMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Pillar -> course rules. Every query is keyed by {@code pillarId}, which the
 * service has already proven belongs to the pipeline on the path — there is no
 * bare-id load on this repository at all.
 */
@Repository
public interface PillarCourseMappingRepository extends JpaRepository<PillarCourseMapping, UUID> {

    List<PillarCourseMapping> findByPillarIdOrderByBandPositionAsc(UUID pillarId);

    /**
     * The read the enrolment engine makes: "band k of pillar p -> which courses",
     * served by {@code idx_pillar_course_mappings_pillar_band} (V150). Ordered by
     * course id purely so a rule set that maps two courses to one band is processed
     * in the same order every run — the engine's tie-breaks have to be repeatable
     * to be explainable.
     */
    List<PillarCourseMapping> findByPillarIdAndBandPositionOrderByCourseIdAsc(UUID pillarId, int bandPosition);

    void deleteByPillarId(UUID pillarId);

    /**
     * Every rule on the platform, pillar and pipeline fetched — the Course
     * visibility screen's AI-rules table. Fetch-joined because the alternative
     * is one lazy load per row on a page that lists them all.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT m FROM PillarCourseMapping m
            JOIN FETCH m.pillar p JOIN FETCH p.pipeline
            ORDER BY p.displayOrder, p.name, m.bandPosition
            """)
    List<PillarCourseMapping> findAllWithPillar();

    /**
     * Copies one pillar's rules onto a freshly cloned pillar — otherwise a clone
     * carries the thresholds but silently drops the rules hanging off them.
     * Every column of the rule, mode included — an AUTO_ASSIGN default here
     * would hard-enrol founders an admin only ever meant to offer.
     * Called AFTER the clone is saved, because {@code target} needs an id.
     *
     * <p>A default method rather than a service so a caller can use it without a
     * Spring cycle ({@code PillarCourseMappingService} depends on
     * {@code PillarService}, which depends on {@code PipelineService}).
     *
     * <p><strong>ponytail: wired into {@code PillarService#duplicate} only.</strong>
     * The two PIPELINE-level clone paths ({@code PipelineService#duplicate} and
     * {@code #createNewVersion}) still lose course rules. Fixing them means
     * injecting this repository into {@code PipelineService}, whose constructor
     * signature is embedded verbatim in SIX frozen ArchUnit violation
     * descriptions — changing it rewrites {@code frozen-violations/**}, which is
     * {@code never_write}. Upgrade path: whichever ticket next has a legitimate
     * reason to change that constructor adds two lines here at the same time.
     */
    default void copyTo(UUID sourcePillarId, Pillar target) {
        saveAll(findByPillarIdOrderByBandPositionAsc(sourcePillarId).stream()
                .map(source -> {
                    PillarCourseMapping copy = new PillarCourseMapping();
                    copy.setPillar(target);
                    copy.setBandPosition(source.getBandPosition());
                    copy.setCourseId(source.getCourseId());
                    copy.setMode(source.getMode());
                    return copy;
                })
                .toList());
    }
}
