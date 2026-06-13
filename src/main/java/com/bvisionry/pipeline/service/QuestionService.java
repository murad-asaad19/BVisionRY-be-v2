package com.bvisionry.pipeline.service;

import com.bvisionry.common.enums.PillarType;
import com.bvisionry.common.enums.QuestionType;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.pipeline.dto.QuestionCreateRequest;
import com.bvisionry.pipeline.dto.QuestionResponse;
import com.bvisionry.pipeline.dto.QuestionUpdateRequest;
import com.bvisionry.pipeline.dto.ReorderRequest;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.entity.Question;
import com.bvisionry.pipeline.repository.QuestionRepository;
import com.bvisionry.pipeline.validation.QuestionConfigValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
public class QuestionService {

    private final QuestionRepository questionRepository;
    private final PipelineService pipelineService;
    private final PillarService pillarService;
    private final QuestionConfigValidator questionConfigValidator;

    @Transactional
    public QuestionResponse create(UUID pipelineId, UUID pillarId, QuestionCreateRequest request) {
        Pipeline pipeline = pipelineService.findPipelineOrThrow(pipelineId);
        pipelineService.requireDraft(pipeline);

        Pillar pillar = pillarService.findPillarOrThrow(pipelineId, pillarId);

        validateQuestionTypeForPillar(request.type(), pillar);

        questionConfigValidator.validate(request.type(), request.configJson());

        int nextOrder = questionRepository.findMaxDisplayOrderByPillarId(pillarId) + 1;

        Question question = new Question();
        question.setPillar(pillar);
        question.setType(request.type());
        question.setPromptText(request.promptText());
        question.setDisplayOrder(nextOrder);
        question.setRequired(request.isRequired() != null ? request.isRequired() : true);
        question.setWeight(request.weight() != null ? request.weight() : BigDecimal.ONE);
        question.setConfigJson(request.configJson());

        Question saved = questionRepository.save(question);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<QuestionResponse> listByPillar(UUID pipelineId, UUID pillarId) {
        pipelineService.findPipelineOrThrow(pipelineId);
        pillarService.findPillarOrThrow(pipelineId, pillarId);
        return questionRepository.findByPillarIdOrderByDisplayOrder(pillarId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public QuestionResponse getById(UUID pipelineId, UUID pillarId, UUID questionId) {
        pipelineService.findPipelineOrThrow(pipelineId);
        pillarService.findPillarOrThrow(pipelineId, pillarId);
        Question question = questionRepository.findByIdAndPillarId(questionId, pillarId)
                .orElseThrow(() -> new ResourceNotFoundException("Question", questionId.toString()));
        return toResponse(question);
    }

    @Transactional
    public QuestionResponse update(UUID pipelineId, UUID pillarId, UUID questionId, QuestionUpdateRequest request) {
        Pipeline pipeline = pipelineService.findPipelineOrThrow(pipelineId);
        pipelineService.requireDraft(pipeline);

        Question question = questionRepository.findByIdAndPillarId(questionId, pillarId)
                .orElseThrow(() -> new ResourceNotFoundException("Question", questionId.toString()));

        if (question.getSystemKey() != null && request.type() != question.getType()) {
            throw new BadRequestException(
                    "The " + question.getPromptText() + " question is required and its type cannot be changed.");
        }

        questionConfigValidator.validate(request.type(), request.configJson());

        question.setType(request.type());
        question.setPromptText(request.promptText());
        question.setRequired(request.isRequired() != null ? request.isRequired() : question.isRequired());
        question.setWeight(request.weight() != null ? request.weight() : question.getWeight());
        question.setConfigJson(request.configJson());

        Question saved = questionRepository.save(question);
        return toResponse(saved);
    }

    @Transactional
    public QuestionResponse duplicate(UUID pipelineId, UUID pillarId, UUID questionId) {
        Pipeline pipeline = pipelineService.findPipelineOrThrow(pipelineId);
        pipelineService.requireDraft(pipeline);

        Pillar pillar = pillarService.findPillarOrThrow(pipelineId, pillarId);

        Question original = questionRepository.findByIdAndPillarId(questionId, pillarId)
                .orElseThrow(() -> new ResourceNotFoundException("Question", questionId.toString()));

        if (original.getSystemKey() != null) {
            throw new BadRequestException(
                    "The " + original.getPromptText() + " question is required and cannot be duplicated.");
        }

        int nextOrder = questionRepository.findMaxDisplayOrderByPillarId(pillarId) + 1;

        Question cloned = new Question();
        cloned.setPillar(pillar);
        cloned.setType(original.getType());
        cloned.setPromptText(original.getPromptText());
        cloned.setDisplayOrder(nextOrder);
        cloned.setRequired(original.isRequired());
        cloned.setWeight(original.getWeight());
        if (original.getConfigJson() != null) {
            cloned.setConfigJson(new LinkedHashMap<>(original.getConfigJson()));
        }

        return toResponse(questionRepository.save(cloned));
    }

    @Transactional
    public void delete(UUID pipelineId, UUID pillarId, UUID questionId) {
        Pipeline pipeline = pipelineService.findPipelineOrThrow(pipelineId);
        pipelineService.requireDraft(pipeline);

        Question question = questionRepository.findByIdAndPillarId(questionId, pillarId)
                .orElseThrow(() -> new ResourceNotFoundException("Question", questionId.toString()));

        if (question.getSystemKey() != null) {
            throw new BadRequestException(
                    "The " + question.getPromptText() + " question is required and cannot be deleted.");
        }

        questionRepository.delete(question);
    }

    @Transactional
    public void reorder(UUID pipelineId, UUID pillarId, ReorderRequest request) {
        Pipeline pipeline = pipelineService.findPipelineOrThrow(pipelineId);
        pipelineService.requireDraft(pipeline);
        pillarService.findPillarOrThrow(pipelineId, pillarId);

        List<Question> existing = questionRepository.findByPillarIdOrderByDisplayOrder(pillarId);

        Set<UUID> existingIds = existing.stream().map(Question::getId).collect(Collectors.toSet());
        Set<UUID> requestedIds = new HashSet<>(request.orderedIds());

        if (!existingIds.equals(requestedIds)) {
            throw new BadRequestException(
                    "Reorder IDs must match existing question IDs for this pillar");
        }

        Map<UUID, Question> questionMap = existing.stream()
                .collect(Collectors.toMap(Question::getId, Function.identity()));

        List<UUID> orderedIds = request.orderedIds();
        for (int i = 0; i < orderedIds.size(); i++) {
            questionMap.get(orderedIds.get(i)).setDisplayOrder(i);
        }

        questionRepository.saveAll(existing);
    }

    private void validateQuestionTypeForPillar(QuestionType questionType, Pillar pillar) {
        boolean isPersonal = pillar.getType() == PillarType.PERSONAL;
        Set<QuestionType> personalOnly = Set.of(QuestionType.DATE, QuestionType.PHONE, QuestionType.EMAIL);
        // MULTIPLE_CHOICE is allowed in Personal pillars (e.g. for the Gender select).
        Set<QuestionType> standardOnly = Set.of(QuestionType.LIKERT, QuestionType.SELF_RATING, QuestionType.MULTI_INPUT);

        if (isPersonal && standardOnly.contains(questionType)) {
            throw new BadRequestException(questionType + " is not available in Personal pillars");
        }
        if (!isPersonal && personalOnly.contains(questionType)) {
            throw new BadRequestException(questionType + " is only available in Personal pillars");
        }
    }

    // --- mapper ---

    private QuestionResponse toResponse(Question q) {
        return new QuestionResponse(
                q.getId(), q.getPillar().getId(), q.getType(), q.getPromptText(),
                q.getDisplayOrder(), q.isRequired(), q.getWeight(),
                q.getConfigJson(), q.getSystemKey(),
                q.getCreatedAt(), q.getUpdatedAt()
        );
    }
}
