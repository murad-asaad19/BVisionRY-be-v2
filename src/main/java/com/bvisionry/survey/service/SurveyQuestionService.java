package com.bvisionry.survey.service;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.survey.dto.SurveyQuestionDto;
import com.bvisionry.survey.dto.SurveyQuestionRequest;
import com.bvisionry.survey.dto.SurveyReorderRequest;
import com.bvisionry.survey.entity.Survey;
import com.bvisionry.survey.entity.SurveyPillar;
import com.bvisionry.survey.entity.SurveyQuestion;
import com.bvisionry.survey.repository.SurveyQuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
public class SurveyQuestionService {

    private final SurveyQuestionRepository questionRepository;
    private final SurveyService surveyService;
    private final SurveyPillarService pillarService;
    private final SurveyQuestionConfigValidator configValidator;
    private final SurveyMapper mapper;

    @Transactional
    public SurveyQuestionDto create(UUID surveyId, UUID pillarId, SurveyQuestionRequest request) {
        Survey survey = surveyService.findOrThrow(surveyId);
        surveyService.requireEditable(survey);

        SurveyPillar pillar = pillarService.findPillarOrThrow(surveyId, pillarId);
        configValidator.validate(request.type(), request.configJson());

        int nextOrder = questionRepository.findMaxDisplayOrderByPillarId(pillarId) + 1;

        SurveyQuestion q = new SurveyQuestion();
        q.setPillar(pillar);
        q.setType(request.type());
        q.setPromptText(request.promptText());
        q.setRequired(request.isRequired() == null ? true : request.isRequired());
        q.setDisplayOrder(nextOrder);
        q.setConfigJson(request.configJson() != null
                ? new LinkedHashMap<>(request.configJson())
                : null);

        return mapper.toQuestionDto(questionRepository.save(q));
    }

    @Transactional
    public SurveyQuestionDto update(UUID surveyId, UUID pillarId, UUID questionId,
                                    SurveyQuestionRequest request) {
        Survey survey = surveyService.findOrThrow(surveyId);
        surveyService.requireEditable(survey);
        pillarService.findPillarOrThrow(surveyId, pillarId);

        SurveyQuestion q = questionRepository.findByIdAndPillarId(questionId, pillarId)
                .orElseThrow(() -> new ResourceNotFoundException("SurveyQuestion", questionId.toString()));

        configValidator.validate(request.type(), request.configJson());

        q.setType(request.type());
        q.setPromptText(request.promptText());
        q.setRequired(request.isRequired() == null ? q.isRequired() : request.isRequired());
        q.setConfigJson(request.configJson() != null
                ? new LinkedHashMap<>(request.configJson())
                : null);

        return mapper.toQuestionDto(questionRepository.save(q));
    }

    /**
     * Toggle whether a question appears on the results "Live" analytics page.
     * Deliberately NOT gated by {@link SurveyService#requireEditable} — this is
     * a display preference, so it's adjustable even after publish, which is
     * exactly when live analytics matters.
     */
    @Transactional
    public SurveyQuestionDto setLiveAnalytics(UUID surveyId, UUID pillarId, UUID questionId,
                                              boolean enabled) {
        surveyService.findOrThrow(surveyId);
        pillarService.findPillarOrThrow(surveyId, pillarId);

        SurveyQuestion q = questionRepository.findByIdAndPillarId(questionId, pillarId)
                .orElseThrow(() -> new ResourceNotFoundException("SurveyQuestion", questionId.toString()));
        q.setLiveAnalyticsEnabled(enabled);
        return mapper.toQuestionDto(questionRepository.save(q));
    }

    @Transactional
    public SurveyQuestionDto duplicate(UUID surveyId, UUID pillarId, UUID questionId) {
        Survey survey = surveyService.findOrThrow(surveyId);
        surveyService.requireEditable(survey);

        SurveyPillar pillar = pillarService.findPillarOrThrow(surveyId, pillarId);

        SurveyQuestion original = questionRepository.findByIdAndPillarId(questionId, pillarId)
                .orElseThrow(() -> new ResourceNotFoundException("SurveyQuestion", questionId.toString()));

        int nextOrder = questionRepository.findMaxDisplayOrderByPillarId(pillarId) + 1;

        SurveyQuestion cloned = new SurveyQuestion();
        cloned.setPillar(pillar);
        cloned.setType(original.getType());
        cloned.setPromptText(original.getPromptText());
        cloned.setRequired(original.isRequired());
        cloned.setLiveAnalyticsEnabled(original.isLiveAnalyticsEnabled());
        cloned.setDisplayOrder(nextOrder);
        if (original.getConfigJson() != null) {
            cloned.setConfigJson(new LinkedHashMap<>(original.getConfigJson()));
        }

        return mapper.toQuestionDto(questionRepository.save(cloned));
    }

    @Transactional
    public void delete(UUID surveyId, UUID pillarId, UUID questionId) {
        Survey survey = surveyService.findOrThrow(surveyId);
        surveyService.requireEditable(survey);
        pillarService.findPillarOrThrow(surveyId, pillarId);

        SurveyQuestion q = questionRepository.findByIdAndPillarId(questionId, pillarId)
                .orElseThrow(() -> new ResourceNotFoundException("SurveyQuestion", questionId.toString()));
        questionRepository.delete(q);
    }

    @Transactional
    public void reorder(UUID surveyId, UUID pillarId, SurveyReorderRequest request) {
        Survey survey = surveyService.findOrThrow(surveyId);
        surveyService.requireEditable(survey);
        pillarService.findPillarOrThrow(surveyId, pillarId);

        List<SurveyQuestion> existing = questionRepository.findByPillarIdOrderByDisplayOrder(pillarId);
        Set<UUID> existingIds = existing.stream().map(SurveyQuestion::getId).collect(Collectors.toSet());
        Set<UUID> requestedIds = new HashSet<>(request.orderedIds());
        if (!existingIds.equals(requestedIds)) {
            throw new BadRequestException("Reorder IDs must match existing question IDs for this pillar");
        }
        Map<UUID, SurveyQuestion> map = existing.stream()
                .collect(Collectors.toMap(SurveyQuestion::getId, Function.identity()));
        List<UUID> orderedIds = request.orderedIds();
        for (int i = 0; i < orderedIds.size(); i++) {
            map.get(orderedIds.get(i)).setDisplayOrder(i);
        }
        questionRepository.saveAll(existing);
    }
}
