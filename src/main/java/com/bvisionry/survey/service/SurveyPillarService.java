package com.bvisionry.survey.service;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.survey.dto.SurveyPillarDto;
import com.bvisionry.survey.dto.SurveyPillarRequest;
import com.bvisionry.survey.dto.SurveyReorderRequest;
import com.bvisionry.survey.entity.Survey;
import com.bvisionry.survey.entity.SurveyPillar;
import com.bvisionry.survey.entity.SurveyQuestion;
import com.bvisionry.survey.repository.SurveyPillarRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
public class SurveyPillarService {

    private final SurveyPillarRepository pillarRepository;
    private final SurveyService surveyService;
    private final SurveyMapper mapper;

    @Transactional
    public SurveyPillarDto create(UUID surveyId, SurveyPillarRequest request) {
        Survey survey = surveyService.findOrThrow(surveyId);
        surveyService.requireEditable(survey);

        int nextOrder = pillarRepository.findMaxDisplayOrderBySurveyId(surveyId) + 1;

        SurveyPillar pillar = new SurveyPillar();
        pillar.setSurvey(survey);
        pillar.setName(request.name());
        pillar.setDescription(request.description());
        pillar.setDisplayOrder(nextOrder);

        return mapper.toPillarDto(pillarRepository.save(pillar));
    }

    @Transactional
    public SurveyPillarDto update(UUID surveyId, UUID pillarId, SurveyPillarRequest request) {
        Survey survey = surveyService.findOrThrow(surveyId);
        surveyService.requireEditable(survey);

        SurveyPillar pillar = pillarRepository.findByIdAndSurveyId(pillarId, surveyId)
                .orElseThrow(() -> new ResourceNotFoundException("SurveyPillar", pillarId.toString()));

        pillar.setName(request.name());
        pillar.setDescription(request.description());
        return mapper.toPillarDto(pillarRepository.save(pillar));
    }

    /**
     * Toggle whether a section appears on the results "Live" analytics page.
     * Deliberately NOT gated by {@link SurveyService#requireEditable} — this is
     * a display preference, so it's adjustable even after publish, which is
     * exactly when live analytics matters.
     */
    @Transactional
    public SurveyPillarDto setLiveAnalytics(UUID surveyId, UUID pillarId, boolean enabled) {
        surveyService.findOrThrow(surveyId);
        SurveyPillar pillar = pillarRepository.findByIdAndSurveyId(pillarId, surveyId)
                .orElseThrow(() -> new ResourceNotFoundException("SurveyPillar", pillarId.toString()));
        pillar.setLiveAnalyticsEnabled(enabled);
        return mapper.toPillarDto(pillarRepository.save(pillar));
    }

    @Transactional
    public SurveyPillarDto duplicate(UUID surveyId, UUID pillarId) {
        Survey survey = surveyService.findOrThrow(surveyId);
        surveyService.requireEditable(survey);

        SurveyPillar original = pillarRepository.findByIdAndSurveyIdWithQuestions(pillarId, surveyId)
                .orElseThrow(() -> new ResourceNotFoundException("SurveyPillar", pillarId.toString()));

        int nextOrder = pillarRepository.findMaxDisplayOrderBySurveyId(surveyId) + 1;

        SurveyPillar cloned = new SurveyPillar();
        cloned.setSurvey(survey);
        cloned.setName(original.getName() + " (Copy)");
        cloned.setDescription(original.getDescription());
        cloned.setDisplayOrder(nextOrder);
        cloned.setLiveAnalyticsEnabled(original.isLiveAnalyticsEnabled());

        List<SurveyQuestion> clonedQuestions = new ArrayList<>();
        for (SurveyQuestion q : original.getQuestions()) {
            SurveyQuestion clonedQ = new SurveyQuestion();
            clonedQ.setPillar(cloned);
            clonedQ.setType(q.getType());
            clonedQ.setPromptText(q.getPromptText());
            clonedQ.setDisplayOrder(q.getDisplayOrder());
            clonedQ.setRequired(q.isRequired());
            if (q.getConfigJson() != null) {
                clonedQ.setConfigJson(new LinkedHashMap<>(q.getConfigJson()));
            }
            clonedQuestions.add(clonedQ);
        }
        cloned.setQuestions(clonedQuestions);

        return mapper.toPillarDto(pillarRepository.save(cloned));
    }

    @Transactional
    public void delete(UUID surveyId, UUID pillarId) {
        Survey survey = surveyService.findOrThrow(surveyId);
        surveyService.requireEditable(survey);

        SurveyPillar pillar = pillarRepository.findByIdAndSurveyId(pillarId, surveyId)
                .orElseThrow(() -> new ResourceNotFoundException("SurveyPillar", pillarId.toString()));

        pillarRepository.delete(pillar);
    }

    @Transactional
    public void reorder(UUID surveyId, SurveyReorderRequest request) {
        Survey survey = surveyService.findOrThrow(surveyId);
        surveyService.requireEditable(survey);

        List<SurveyPillar> existing = pillarRepository.findBySurveyIdOrderByDisplayOrder(surveyId);
        Set<UUID> existingIds = existing.stream().map(SurveyPillar::getId).collect(Collectors.toSet());
        Set<UUID> requestedIds = new HashSet<>(request.orderedIds());
        if (!existingIds.equals(requestedIds)) {
            throw new BadRequestException("Reorder IDs must match existing pillar IDs for this survey");
        }

        Map<UUID, SurveyPillar> map = existing.stream()
                .collect(Collectors.toMap(SurveyPillar::getId, Function.identity()));
        List<UUID> orderedIds = request.orderedIds();
        for (int i = 0; i < orderedIds.size(); i++) {
            map.get(orderedIds.get(i)).setDisplayOrder(i);
        }
        pillarRepository.saveAll(existing);
    }

    SurveyPillar findPillarOrThrow(UUID surveyId, UUID pillarId) {
        return pillarRepository.findByIdAndSurveyId(pillarId, surveyId)
                .orElseThrow(() -> new ResourceNotFoundException("SurveyPillar", pillarId.toString()));
    }
}
