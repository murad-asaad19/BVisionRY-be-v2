package com.bvisionry.pipeline.service;

import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.pipeline.dto.PillarCreateRequest;
import com.bvisionry.pipeline.dto.PillarResponse;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.repository.PillarCourseMappingRepository;
import com.bvisionry.pipeline.repository.PillarRepository;
import com.bvisionry.pipeline.validation.IconKeyValidator;
import com.bvisionry.pipeline.validation.MaturityThresholdValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The default band set is applied in exactly one place. These pin that place —
 * the editor no longer sends thresholds, so a regression here would silently
 * band every new pillar differently from every existing one.
 *
 * <p>{@link MaturityThresholdValidator} is the REAL collaborator, not a mock:
 * that makes the default prove it satisfies the same contiguous-0-100 rule a
 * hand-authored set must satisfy.
 */
@ExtendWith(MockitoExtension.class)
class PillarServiceTest {

    @Mock private PillarRepository pillarRepository;
    @Mock private PillarCourseMappingRepository courseMappingRepository;
    @Mock private PipelineService pipelineService;
    @Mock private CurrentUserAccessor currentUser;

    private PillarService service;

    private final UUID pipelineId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PillarService(pillarRepository, courseMappingRepository, pipelineService,
                new IconKeyValidator(), new MaturityThresholdValidator(), currentUser);

        Pipeline pipeline = new Pipeline();
        pipeline.setId(pipelineId);
        when(pipelineService.findPipelineOrThrow(pipelineId)).thenReturn(pipeline);
        when(pillarRepository.findMaxDisplayOrderByPipelineId(pipelineId)).thenReturn(-1);
        when(pillarRepository.save(any(Pillar.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private PillarResponse create(Map<String, List<Integer>> thresholds) {
        return service.create(pipelineId,
                new PillarCreateRequest("Vision Clarity", null, null, null, null, thresholds, null));
    }

    @Test
    void omittedThresholds_fallBackToTheOnePlatformDefault() {
        assertThat(create(null).maturityThresholds())
                .isEqualTo(Map.of("Emerging", List.of(0, 59),
                        "Strong", List.of(60, 79),
                        "Elite", List.of(80, 100)));
    }

    @Test
    void suppliedThresholds_areKeptExactly_becauseBandsArePerPillarConfiguration() {
        Map<String, List<Integer>> bespoke = Map.of(
                "Redline", List.of(0, 40),
                "Balanced", List.of(41, 74),
                "Battery Charged", List.of(75, 100));

        assertThat(create(bespoke).maturityThresholds()).isEqualTo(bespoke);
    }
}
