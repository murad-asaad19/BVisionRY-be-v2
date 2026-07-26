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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Band identity is ORDINAL POSITION within the pillar's own set (RULING 4), and
 * these pin the two consequences that are easy to get wrong later:
 *
 * <ul>
 *   <li>the position is derived by SORTING on minimum score, never by map
 *       iteration order — the column is {@code jsonb}, which does not preserve
 *       key order, so a labels-in-insertion-order assumption would silently map
 *       the wrong band;</li>
 *   <li>shrinking a pillar's band set neither deletes nor re-points an existing
 *       rule: it is reported with a null band and left alone.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PillarCourseMappingServiceTest {

    @Mock private PillarCourseMappingRepository mappingRepository;
    @Mock private PillarService pillarService;
    @Mock private CourseCatalogReadRepository courseCatalog;

    private PillarCourseMappingService service;

    private final UUID pipelineId = UUID.randomUUID();
    private final UUID pillarId = UUID.randomUUID();
    private final UUID courseId = UUID.randomUUID();

    private Pillar pillar;

    @BeforeEach
    void setUp() {
        service = new PillarCourseMappingService(mappingRepository, pillarService, courseCatalog);

        pillar = new Pillar();
        pillar.setId(pillarId);
        pillar.setType(PillarType.STANDARD);
        // Deliberately NOT in ascending order: the service must sort by minimum
        // score, because jsonb hands back whatever order it likes.
        Map<String, List<Integer>> thresholds = new LinkedHashMap<>();
        thresholds.put("Elite", List.of(80, 100));
        thresholds.put("Emerging", List.of(0, 59));
        thresholds.put("Strong", List.of(60, 79));
        pillar.setMaturityThresholds(thresholds);
    }

    private void pillarIsFound() {
        when(pillarService.findPillarOrThrow(pipelineId, pillarId)).thenReturn(pillar);
    }

    private PillarCourseMapping storedMapping(int bandPosition, UUID course) {
        PillarCourseMapping mapping = new PillarCourseMapping();
        mapping.setId(UUID.randomUUID());
        mapping.setPillar(pillar);
        mapping.setBandPosition(bandPosition);
        mapping.setCourseId(course);
        return mapping;
    }

    private void catalogKnows(UUID id, String title, String state) {
        when(courseCatalog.findByIdsUnscoped(anyCollection()))
                .thenReturn(Map.of(id, new CourseRef(id, title, state)));
    }

    private PillarCourseMappingsRequest body(PillarCourseMappingItem... items) {
        return new PillarCourseMappingsRequest(List.of(items));
    }

    @Test
    void position0IsTheWEAKESTBand_notTheFirstMapKey() {
        pillarIsFound();
        catalogKnows(courseId, "Fundraising Basics", "PUBLISHED");
        when(mappingRepository.findByPillarIdOrderByBandPositionAsc(pillarId))
                .thenReturn(List.of(storedMapping(0, courseId)));

        PillarCourseMappingResponse row = service.list(pipelineId, pillarId).getFirst();

        assertThat(row.bandLabel()).isEqualTo("Emerging");
        assertThat(row.bandMinScore()).isZero();
        assertThat(row.bandMaxScore()).isEqualTo(59);
        assertThat(row.courseTitle()).isEqualTo("Fundraising Basics");
        assertThat(row.courseState()).isEqualTo("PUBLISHED");
    }

    @Test
    void shrinkingTheBandSetStrandsTheRuleRatherThanDeletingOrRepointingIt() {
        pillarIsFound();
        catalogKnows(courseId, "Fundraising Basics", "PUBLISHED");
        // The admin has since collapsed three bands into two; position 2 is gone.
        pillar.setMaturityThresholds(Map.of("Weak", List.of(0, 59), "Ready", List.of(60, 100)));
        when(mappingRepository.findByPillarIdOrderByBandPositionAsc(pillarId))
                .thenReturn(List.of(storedMapping(2, courseId)));

        PillarCourseMappingResponse row = service.list(pipelineId, pillarId).getFirst();

        assertThat(row.bandPosition()).isEqualTo(2);
        assertThat(row.bandLabel()).isNull();
        assertThat(row.bandMinScore()).isNull();
        assertThat(row.bandMaxScore()).isNull();
        // The rule itself survived — the course end still resolves.
        assertThat(row.courseTitle()).isEqualTo("Fundraising Basics");
        verify(mappingRepository, never()).deleteByPillarId(any());
    }

    @Test
    void aDeletedCourseLeavesTheRuleVisibleWithNoTitleRatherThanVanishing() {
        pillarIsFound();
        when(courseCatalog.findByIdsUnscoped(anyCollection())).thenReturn(Map.of());
        when(mappingRepository.findByPillarIdOrderByBandPositionAsc(pillarId))
                .thenReturn(List.of(storedMapping(1, courseId)));

        PillarCourseMappingResponse row = service.list(pipelineId, pillarId).getFirst();

        assertThat(row.bandLabel()).isEqualTo("Strong");
        assertThat(row.courseTitle()).isNull();
        assertThat(row.courseState()).isNull();
    }

    @Test
    void replaceStoresTheRulesAndAnswersWithTheirResolvedBands() {
        pillarIsFound();
        catalogKnows(courseId, "Fundraising Basics", "DRAFT");
        when(mappingRepository.saveAll(any())).thenAnswer(inv -> {
            List<PillarCourseMapping> given = inv.getArgument(0);
            given.forEach(m -> m.setId(UUID.randomUUID()));
            return given;
        });

        List<PillarCourseMappingResponse> saved = service.replace(pipelineId, pillarId,
                body(new PillarCourseMappingItem(0, courseId)));

        // The DELETE must reach the database before the INSERTs or the unique
        // index rejects re-saving an unchanged rule.
        var order = org.mockito.Mockito.inOrder(mappingRepository);
        order.verify(mappingRepository).deleteByPillarId(pillarId);
        order.verify(mappingRepository).flush();
        order.verify(mappingRepository).saveAll(any());

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.bandLabel()).isEqualTo("Emerging");
            // A DRAFT course is reported, not hidden: the admin is entitled to
            // know why the rule they just wrote will not reach anyone.
            assertThat(row.courseState()).isEqualTo("DRAFT");
        });
    }

    @Test
    void anEmptyListClearsThePillarsRules() {
        pillarIsFound();
        when(courseCatalog.findByIdsUnscoped(anyCollection())).thenReturn(Map.of());
        when(mappingRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.replace(pipelineId, pillarId, body())).isEmpty();
        verify(mappingRepository).deleteByPillarId(pillarId);
    }

    @Test
    void aBandPositionPastTheEndIsRejectedRatherThanStoredStranded() {
        pillarIsFound();

        assertThatThrownBy(() -> service.replace(pipelineId, pillarId,
                body(new PillarCourseMappingItem(3, courseId))))
                .isInstanceOf(BadRequestException.class)
                // 1-based and named, matching how the admin UI numbers bands.
                .hasMessageContaining("Band 4 does not exist")
                .hasMessageContaining("It has 3: 1 Emerging (0-59%), 2 Strong (60-79%), 3 Elite (80-100%)");

        verify(mappingRepository, never()).deleteByPillarId(any());
    }

    /**
     * @Min(0) on the DTO already rejects this at the edge. The service checks it
     * again because if that cascade ever regresses, `bands.get(-1)` turns a bad
     * request into a 500 — and the duplicate key would collide before that.
     */
    @Test
    void aNegativeBandPositionIsRejectedRatherThanIndexingOutOfBounds() {
        pillarIsFound();

        assertThatThrownBy(() -> service.replace(pipelineId, pillarId,
                body(new PillarCourseMappingItem(-1, courseId))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("does not exist");
    }

    /**
     * `courseState` is what {@code auto_enrolment} must filter on — this surface
     * deliberately does not, so a founder would otherwise be enrolled into a
     * DRAFT course. Pinned here so the field cannot be dropped as decoration
     * before the consumer that needs it exists.
     */
    @Test
    void everyRowCarriesCourseStateBecauseTheConsumerMustRefuseOnIt() {
        pillarIsFound();
        catalogKnows(courseId, "Fundraising Basics", "ARCHIVED");
        when(mappingRepository.findByPillarIdOrderByBandPositionAsc(pillarId))
                .thenReturn(List.of(storedMapping(0, courseId), storedMapping(1, courseId)));

        assertThat(service.list(pipelineId, pillarId))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.courseState()).isEqualTo("ARCHIVED"));
    }

    /**
     * The column predates {@link com.bvisionry.pipeline.validation.MaturityThresholdValidator},
     * so legacy jsonb can hold a null bound. Listing a pillar's rules must not
     * NPE on unboxing it.
     */
    @Test
    void aLegacyBandWithANullBoundIsSkippedRatherThanThrowing() {
        pillarIsFound();
        catalogKnows(courseId, "Fundraising Basics", "PUBLISHED");
        Map<String, List<Integer>> legacy = new LinkedHashMap<>();
        legacy.put("Broken", java.util.Arrays.asList(null, 59));
        legacy.put("Fine", List.of(0, 100));
        pillar.setMaturityThresholds(legacy);
        when(mappingRepository.findByPillarIdOrderByBandPositionAsc(pillarId))
                .thenReturn(List.of(storedMapping(0, courseId)));

        assertThat(service.list(pipelineId, pillarId))
                .singleElement()
                .satisfies(row -> assertThat(row.bandLabel()).isEqualTo("Fine"));
    }

    @Test
    void theSameCourseTwiceInOneBandIsRejected() {
        pillarIsFound();

        assertThatThrownBy(() -> service.replace(pipelineId, pillarId,
                body(new PillarCourseMappingItem(1, courseId),
                        new PillarCourseMappingItem(1, courseId))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Strong");
    }

    @Test
    void theSameCourseInTwoDifferentBandsIsAllowed() {
        pillarIsFound();
        catalogKnows(courseId, "Fundraising Basics", "PUBLISHED");
        when(mappingRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.replace(pipelineId, pillarId,
                body(new PillarCourseMappingItem(0, courseId),
                        new PillarCourseMappingItem(1, courseId))))
                .extracting(PillarCourseMappingResponse::bandLabel)
                .containsExactly("Emerging", "Strong");
    }

    @Test
    void aCourseThatDoesNotExistIsRejectedBeforeAnythingIsWritten() {
        pillarIsFound();
        when(courseCatalog.findByIdsUnscoped(anyCollection())).thenReturn(Map.of());

        assertThatThrownBy(() -> service.replace(pipelineId, pillarId,
                body(new PillarCourseMappingItem(0, courseId))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(courseId.toString());

        verify(mappingRepository, never()).deleteByPillarId(any());
    }

    @Test
    void thePersonalPillarHasNoBandsSoItTakesNoRules() {
        pillarIsFound();
        pillar.setType(PillarType.PERSONAL);
        pillar.setMaturityThresholds(Map.of());

        assertThatThrownBy(() -> service.replace(pipelineId, pillarId,
                body(new PillarCourseMappingItem(0, courseId))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("General Information");
    }

    @Test
    void aPillarWithNoThresholdsIsToldToConfigureThemFirst() {
        pillarIsFound();
        pillar.setMaturityThresholds(null);

        assertThatThrownBy(() -> service.replace(pipelineId, pillarId,
                body(new PillarCourseMappingItem(0, courseId))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no maturity bands");
    }
}
