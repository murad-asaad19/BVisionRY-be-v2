package com.bvisionry.pipeline.service;

import com.bvisionry.common.enums.PillarType;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.pipeline.dto.PillarCourseMappingItem;
import com.bvisionry.pipeline.dto.PillarCourseMappingResponse;
import com.bvisionry.pipeline.dto.PillarCourseMappingsRequest;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.PillarCourseMapping;
import com.bvisionry.pipeline.repository.CourseCatalogReadRepository;
import com.bvisionry.pipeline.repository.CourseCatalogReadRepository.CourseRef;
import com.bvisionry.pipeline.repository.PillarCourseMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Which courses address this pillar at which score band" (roadmap §7 item 9).
 *
 * <p>CONFIG ONLY. Nothing here enrols anyone or reads a founder's score; the
 * engine that consumes these rules is the next ticket ({@code auto_enrolment}).
 *
 * <p><strong>Band identity is ordinal position</strong> (agent-decisions
 * RULING 4). Bands are per-pillar configurable data with bespoke per-customer
 * vocabularies, so the stored key is the band's 0-based index within the
 * pillar's OWN set ordered lowest -> highest, and every read re-resolves that
 * index against the pillar's thresholds as they stand now.
 *
 * <p><strong>Editing a pillar's bands afterwards does not touch these rows.</strong>
 * Positions are never renumbered, nothing cascades, and the band edit is never
 * blocked. A position past the end of a shrunk band set is reported with a null
 * band (see {@link PillarCourseMappingResponse}) and is inert until the set
 * widens again — a score always lands in a band that exists, so a stranded
 * position can never be selected. Deleting on the admin's behalf would lose
 * config they did not ask to lose; blocking would make the measurement
 * instrument hostage to a downstream recommendation.
 *
 * <p><strong>What actually makes that safe is the DRAFT freeze, not
 * re-labelling.</strong> Re-labelling is merely VISIBLE; a same-label re-SPLIT
 * is not. Going from {@code {Weak 0-49, Ready 50-100}} to
 * {@code {Weak 0-19, Middling 20-49, Ready 50-100}} leaves position 0 still
 * reading "Weak" while the rule it carries silently narrows from 0-49 to 0-19 —
 * nothing on screen changes. The reason no founder is affected by that is
 * {@code PillarService#update} calling {@code requireDraft}: a PUBLISHED
 * pipeline's bands cannot move at all, so a re-split can only happen on a draft,
 * before anyone is measured or enrolled by it. If that freeze is ever relaxed,
 * this whole "keep the position, re-resolve on read" scheme needs revisiting.
 *
 * <p><strong>Declared at platform grain by SUPER_ADMIN</strong>, which is where
 * both ends already live: pillars are global content only SUPER_ADMIN may
 * author, and the catalog is not org-filtered. Authorization is the class-level
 * gate on {@code PillarController} plus the explicit annotation on each handler;
 * the data layer scopes every load by {@code (pillarId, pipelineId)} so a pillar
 * id from another pipeline 404s.
 *
 * <p><strong>Not gated on DRAFT</strong>, unlike everything else that edits a
 * pillar. {@code requireDraft} exists so a published pipeline's SCORING cannot
 * move under founders who were already measured by it. A recommendation rule
 * changes no score and rewrites no stored evaluation — freezing it would mean
 * unpublishing a live pipeline to fix a bad course pointer, which is the one
 * genuinely dangerous edit on this surface.
 */
@Service
@RequiredArgsConstructor
public class PillarCourseMappingService {

    private final PillarCourseMappingRepository mappingRepository;
    private final PillarService pillarService;
    private final CourseCatalogReadRepository courseCatalog;

    /** One band of a pillar, resolved from its thresholds at its ordinal position. */
    private record Band(String label, int min, int max) {}

    @Transactional(readOnly = true)
    public List<PillarCourseMappingResponse> list(UUID pipelineId, UUID pillarId) {
        Pillar pillar = pillarService.findPillarOrThrow(pipelineId, pillarId);
        return toResponses(pillar, mappingRepository.findByPillarIdOrderByBandPositionAsc(pillarId));
    }

    /**
     * Replaces the pillar's whole rule set. Everything is validated before
     * anything is written, so a rejected request leaves the existing rules
     * exactly as they were.
     */
    @Transactional
    public List<PillarCourseMappingResponse> replace(
            UUID pipelineId, UUID pillarId, PillarCourseMappingsRequest request) {

        Pillar pillar = pillarService.findPillarOrThrow(pipelineId, pillarId);

        if (pillar.getType() == PillarType.PERSONAL) {
            throw new BadRequestException(
                    "The General Information pillar is not scored, so it has no bands to map courses to.");
        }

        List<Band> bands = bandsOf(pillar);
        if (bands.isEmpty()) {
            throw new BadRequestException(
                    "This pillar has no maturity bands configured. Set its thresholds first.");
        }

        Set<String> seen = new HashSet<>();
        for (PillarCourseMappingItem item : request.mappings()) {
            // Both ends, in one place. The lower bound is also enforced by
            // @Min(0) on the DTO, but a regression in the @Valid cascade would
            // otherwise reach bands.get(negative) and turn a bad request into a 500.
            if (item.bandPosition() < 0 || item.bandPosition() >= bands.size()) {
                throw new BadRequestException(
                        "Band %d does not exist on this pillar. It has %d: %s."
                                .formatted(item.bandPosition() + 1, bands.size(), bandList(bands)));
            }
            if (!seen.add(item.bandPosition() + ":" + item.courseId())) {
                throw new BadRequestException(
                        "The same course is mapped twice to band '%s'.".formatted(bands.get(item.bandPosition()).label()));
            }
        }

        Map<UUID, CourseRef> courses = courseCatalog.findByIdsUnscoped(
                request.mappings().stream().map(PillarCourseMappingItem::courseId).collect(Collectors.toSet()));
        List<UUID> missing = request.mappings().stream()
                .map(PillarCourseMappingItem::courseId)
                .filter(id -> !courses.containsKey(id))
                .distinct()
                .toList();
        if (!missing.isEmpty()) {
            throw new BadRequestException("No course exists with id " + missing.getFirst()
                    + ". It may have been deleted — reload the course list.");
        }

        // Replace wholesale. `flush` forces the DELETE to hit the database before
        // the INSERTs, so re-saving an unchanged rule cannot collide with its own
        // predecessor on the (pillar, band, course) unique index.
        mappingRepository.deleteByPillarId(pillarId);
        mappingRepository.flush();

        List<PillarCourseMapping> saved = mappingRepository.saveAll(
                request.mappings().stream().map(item -> {
                    PillarCourseMapping mapping = new PillarCourseMapping();
                    mapping.setPillar(pillar);
                    mapping.setBandPosition(item.bandPosition());
                    mapping.setCourseId(item.courseId());
                    return mapping;
                }).toList());

        return toResponses(pillar, saved);
    }

    /** The bands as the admin sees them numbered on screen: 1-based, named. */
    private static String bandList(List<Band> bands) {
        return java.util.stream.IntStream.range(0, bands.size())
                .mapToObj(i -> "%d %s (%d-%d%%)".formatted(
                        i + 1, bands.get(i).label(), bands.get(i).min(), bands.get(i).max()))
                .collect(Collectors.joining(", "));
    }

    /**
     * The pillar's bands in ordinal order — sorted by minimum score, which is
     * the only ordering {@code MaturityThresholdValidator} guarantees is total
     * (it enforces a contiguous 1..N partition of 0-100) and the same order the
     * threshold editor renders. {@code jsonb} does not preserve key order, so
     * map iteration order is never trusted.
     *
     * <p>The filter checks the ELEMENTS, not just the size: the column predates
     * the validator, so legacy jsonb can hold {@code [null, 59]}, which the
     * comparator would unbox into an NPE while listing a pillar's rules.
     */
    private static List<Band> bandsOf(Pillar pillar) {
        Map<String, List<Integer>> thresholds = pillar.getMaturityThresholds();
        if (thresholds == null) {
            return List.of();
        }
        return thresholds.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().size() == 2
                        && e.getValue().get(0) != null && e.getValue().get(1) != null)
                .sorted(Comparator.comparingInt(
                        (Map.Entry<String, List<Integer>> e) -> e.getValue().get(0)))
                .map(e -> new Band(e.getKey(), e.getValue().get(0), e.getValue().get(1)))
                .toList();
    }

    private List<PillarCourseMappingResponse> toResponses(
            Pillar pillar, List<PillarCourseMapping> mappings) {

        List<Band> bands = bandsOf(pillar);
        Map<UUID, CourseRef> courses = courseCatalog.findByIdsUnscoped(
                mappings.stream().map(PillarCourseMapping::getCourseId).collect(Collectors.toSet()));

        return mappings.stream()
                .map(mapping -> {
                    // Null when the band set was shrunk under this rule: the row is
                    // kept and marked, never silently deleted or re-pointed.
                    Band band = mapping.getBandPosition() < bands.size()
                            ? bands.get(mapping.getBandPosition())
                            : null;
                    CourseRef course = courses.get(mapping.getCourseId());
                    return new PillarCourseMappingResponse(
                            mapping.getId(),
                            pillar.getId(),
                            mapping.getBandPosition(),
                            band == null ? null : band.label(),
                            band == null ? null : band.min(),
                            band == null ? null : band.max(),
                            mapping.getCourseId(),
                            course == null ? null : course.title(),
                            course == null ? null : course.state());
                })
                .sorted(Comparator.comparingInt(PillarCourseMappingResponse::bandPosition)
                        .thenComparing((PillarCourseMappingResponse r) ->
                                r.courseTitle() == null ? "" : r.courseTitle()))
                .toList();
    }
}
