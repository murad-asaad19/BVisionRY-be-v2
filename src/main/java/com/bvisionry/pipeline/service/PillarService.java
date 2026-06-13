package com.bvisionry.pipeline.service;

import com.bvisionry.common.enums.PillarType;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.pipeline.dto.PillarCreateRequest;
import com.bvisionry.pipeline.dto.PillarResponse;
import com.bvisionry.pipeline.dto.PillarUpdateRequest;
import com.bvisionry.pipeline.dto.QuestionResponse;
import com.bvisionry.pipeline.dto.ReorderRequest;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.entity.Question;
import com.bvisionry.pipeline.repository.PillarRepository;
import com.bvisionry.pipeline.validation.IconKeyValidator;
import com.bvisionry.pipeline.validation.MaturityThresholdValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PillarService {

    private final PillarRepository pillarRepository;
    private final PipelineService pipelineService;
    private final IconKeyValidator iconKeyValidator;
    private final MaturityThresholdValidator maturityThresholdValidator;

    @Transactional
    public PillarResponse create(UUID pipelineId, PillarCreateRequest request) {
        Pipeline pipeline = pipelineService.findPipelineOrThrow(pipelineId);
        pipelineService.requireDraft(pipeline);

        boolean isPersonal = "PERSONAL".equals(request.type());

        iconKeyValidator.validate(request.iconKey());
        if (!isPersonal) {
            maturityThresholdValidator.validate(request.maturityThresholds());
        }

        int nextOrder = pillarRepository.findMaxDisplayOrderByPipelineId(pipelineId) + 1;

        Pillar pillar = new Pillar();
        pillar.setPipeline(pipeline);
        pillar.setName(request.name());

        // Determine pillar type
        PillarType pillarType = PillarType.STANDARD;
        if (isPersonal) {
            boolean hasPersonal = pillarRepository.findByPipelineIdOrderByDisplayOrder(pipelineId)
                    .stream().anyMatch(p -> p.getType() == PillarType.PERSONAL);
            if (hasPersonal) {
                throw new BadRequestException("Pipeline already has a Personal pillar");
            }
            pillarType = PillarType.PERSONAL;
        }
        pillar.setType(pillarType);

        pillar.setDescription(request.description());
        pillar.setIconKey(request.iconKey());
        pillar.setDisplayOrder(nextOrder);

        if (pillarType == PillarType.PERSONAL) {
            pillar.setWeight(BigDecimal.ZERO);
            pillar.setAiRubricInstructions(null);
            pillar.setMaturityThresholds(Map.of());
        } else {
            pillar.setWeight(request.weight() != null ? request.weight() : BigDecimal.ONE);
            pillar.setAiRubricInstructions(request.aiRubricInstructions());
            pillar.setMaturityThresholds(request.maturityThresholds());
        }

        Pillar saved = pillarRepository.save(pillar);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<PillarResponse> listByPipeline(UUID pipelineId) {
        pipelineService.findPipelineOrThrow(pipelineId);
        return pillarRepository.findByPipelineIdOrderByDisplayOrder(pipelineId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PillarResponse getById(UUID pipelineId, UUID pillarId) {
        pipelineService.findPipelineOrThrow(pipelineId);
        Pillar pillar = pillarRepository.findByIdAndPipelineIdWithQuestions(pillarId, pipelineId)
                .orElseThrow(() -> new ResourceNotFoundException("Pillar", pillarId.toString()));
        return toResponse(pillar);
    }

    @Transactional
    public PillarResponse update(UUID pipelineId, UUID pillarId, PillarUpdateRequest request) {
        Pipeline pipeline = pipelineService.findPipelineOrThrow(pipelineId);
        pipelineService.requireDraft(pipeline);

        Pillar pillar = pillarRepository.findByIdAndPipelineId(pillarId, pipelineId)
                .orElseThrow(() -> new ResourceNotFoundException("Pillar", pillarId.toString()));

        iconKeyValidator.validate(request.iconKey());
        if (pillar.getType() != PillarType.PERSONAL) {
            maturityThresholdValidator.validate(request.maturityThresholds());
        }

        if (pillar.getType() == PillarType.PERSONAL) {
            pillar.setName(request.name());
            pillar.setDescription(request.description());
            pillar.setIconKey(request.iconKey());
        } else {
            pillar.setName(request.name());
            pillar.setDescription(request.description());
            pillar.setIconKey(request.iconKey());
            pillar.setWeight(request.weight() != null ? request.weight() : pillar.getWeight());
            pillar.setAiRubricInstructions(request.aiRubricInstructions());
            pillar.setMaturityThresholds(request.maturityThresholds());
        }

        Pillar saved = pillarRepository.save(pillar);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID pipelineId, UUID pillarId) {
        Pipeline pipeline = pipelineService.findPipelineOrThrow(pipelineId);
        pipelineService.requireDraft(pipeline);

        Pillar pillar = pillarRepository.findByIdAndPipelineId(pillarId, pipelineId)
                .orElseThrow(() -> new ResourceNotFoundException("Pillar", pillarId.toString()));

        if (pillar.getType() == PillarType.PERSONAL) {
            throw new BadRequestException(
                    "The General Information pillar is required and cannot be deleted.");
        }

        pillarRepository.delete(pillar);
    }

    @Transactional
    public void reorder(UUID pipelineId, ReorderRequest request) {
        Pipeline pipeline = pipelineService.findPipelineOrThrow(pipelineId);
        pipelineService.requireDraft(pipeline);

        List<Pillar> existing = pillarRepository.findByPipelineIdOrderByDisplayOrder(pipelineId);

        Set<UUID> existingIds = existing.stream().map(Pillar::getId).collect(Collectors.toSet());
        Set<UUID> requestedIds = new HashSet<>(request.orderedIds());

        if (!existingIds.equals(requestedIds)) {
            throw new BadRequestException(
                    "Reorder IDs must match existing pillar IDs for this pipeline");
        }

        Map<UUID, Pillar> pillarMap = existing.stream()
                .collect(Collectors.toMap(Pillar::getId, Function.identity()));

        List<UUID> orderedIds = request.orderedIds();
        for (int i = 0; i < orderedIds.size(); i++) {
            pillarMap.get(orderedIds.get(i)).setDisplayOrder(i);
        }

        pillarRepository.saveAll(existing);
    }

    @Transactional
    public PillarResponse duplicate(UUID pipelineId, UUID pillarId) {
        Pipeline pipeline = pipelineService.findPipelineOrThrow(pipelineId);
        pipelineService.requireDraft(pipeline);

        Pillar original = pillarRepository.findByIdAndPipelineIdWithQuestions(pillarId, pipelineId)
                .orElseThrow(() -> new ResourceNotFoundException("Pillar", pillarId.toString()));

        if (original.getType() == PillarType.PERSONAL) {
            throw new BadRequestException("Personal pillars cannot be duplicated");
        }

        int nextOrder = pillarRepository.findMaxDisplayOrderByPipelineId(pipelineId) + 1;

        Pillar cloned = new Pillar();
        cloned.setPipeline(pipeline);
        cloned.setName(original.getName() + " (Copy)");
        cloned.setDescription(original.getDescription());
        cloned.setIconKey(original.getIconKey());
        cloned.setWeight(original.getWeight());
        cloned.setDisplayOrder(nextOrder);
        cloned.setAiRubricInstructions(original.getAiRubricInstructions());

        if (original.getMaturityThresholds() != null) {
            cloned.setMaturityThresholds(new LinkedHashMap<>(original.getMaturityThresholds()));
        }

        List<Question> clonedQuestions = new ArrayList<>();
        for (Question q : original.getQuestions()) {
            Question clonedQ = new Question();
            clonedQ.setPillar(cloned);
            clonedQ.setType(q.getType());
            clonedQ.setPromptText(q.getPromptText());
            clonedQ.setDisplayOrder(q.getDisplayOrder());
            clonedQ.setRequired(q.isRequired());
            clonedQ.setWeight(q.getWeight());
            if (q.getConfigJson() != null) {
                clonedQ.setConfigJson(new LinkedHashMap<>(q.getConfigJson()));
            }
            clonedQuestions.add(clonedQ);
        }
        cloned.setQuestions(clonedQuestions);

        Pillar saved = pillarRepository.save(cloned);
        return toResponse(saved);
    }

    Pillar findPillarOrThrow(UUID pipelineId, UUID pillarId) {
        return pillarRepository.findByIdAndPipelineId(pillarId, pipelineId)
                .orElseThrow(() -> new ResourceNotFoundException("Pillar", pillarId.toString()));
    }

    // --- mappers ---

    private PillarResponse toResponse(Pillar pillar) {
        List<QuestionResponse> questionResponses = pillar.getQuestions() != null
                ? pillar.getQuestions().stream().map(this::toQuestionResponse).toList()
                : List.of();

        return new PillarResponse(
                pillar.getId(), pillar.getPipeline().getId(),
                pillar.getName(), pillar.getDescription(), pillar.getIconKey(),
                pillar.getWeight(), pillar.getDisplayOrder(),
                pillar.getType().name(),
                pillar.getAiRubricInstructions(), pillar.getMaturityThresholds(),
                questionResponses, pillar.getCreatedAt(), pillar.getUpdatedAt()
        );
    }

    private QuestionResponse toQuestionResponse(Question q) {
        return new QuestionResponse(
                q.getId(), q.getPillar().getId(), q.getType(), q.getPromptText(),
                q.getDisplayOrder(), q.isRequired(), q.getWeight(),
                q.getConfigJson(), q.getSystemKey(),
                q.getCreatedAt(), q.getUpdatedAt()
        );
    }
}
