package com.bvisionry.survey.service;

import com.bvisionry.survey.dto.MemberSurveyDto;
import com.bvisionry.survey.dto.PublicSurveyDto;
import com.bvisionry.survey.dto.PublicSurveyPillarDto;
import com.bvisionry.survey.dto.PublicSurveyQuestionDto;
import com.bvisionry.survey.dto.SurveyDto;
import com.bvisionry.survey.dto.SurveyPillarDto;
import com.bvisionry.survey.dto.SurveyQuestionDto;
import com.bvisionry.survey.entity.Survey;
import com.bvisionry.survey.entity.SurveyPillar;
import com.bvisionry.survey.entity.SurveyQuestion;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class SurveyMapper {

    public SurveyDto toSurveyDto(Survey survey) {
        List<SurveyPillarDto> pillarDtos = survey.getPillars() != null
                ? survey.getPillars().stream()
                        .sorted(Comparator.comparingInt(SurveyPillar::getDisplayOrder))
                        .map(this::toPillarDto)
                        .toList()
                : List.of();
        return new SurveyDto(
                survey.getId(),
                survey.getName(),
                survey.getDescription(),
                survey.getStatus(),
                survey.getVisibility(),
                survey.getPublicToken(),
                survey.getPublishedAt(),
                survey.getClosedAt(),
                survey.getRespondentEmailMode(),
                survey.getRespondentNameMode(),
                survey.getCreatedBy(),
                pillarDtos,
                survey.getCreatedAt(),
                survey.getUpdatedAt()
        );
    }

    public SurveyPillarDto toPillarDto(SurveyPillar pillar) {
        List<SurveyQuestionDto> questionDtos = pillar.getQuestions() != null
                ? pillar.getQuestions().stream()
                        .sorted(Comparator.comparingInt(SurveyQuestion::getDisplayOrder))
                        .map(this::toQuestionDto)
                        .toList()
                : List.of();
        return new SurveyPillarDto(
                pillar.getId(),
                pillar.getSurvey().getId(),
                pillar.getName(),
                pillar.getDescription(),
                pillar.getDisplayOrder(),
                questionDtos,
                pillar.getCreatedAt(),
                pillar.getUpdatedAt()
        );
    }

    public SurveyQuestionDto toQuestionDto(SurveyQuestion q) {
        return new SurveyQuestionDto(
                q.getId(),
                q.getPillar().getId(),
                q.getType(),
                q.getPromptText(),
                q.getDisplayOrder(),
                q.isRequired(),
                q.getConfigJson(),
                q.getCreatedAt(),
                q.getUpdatedAt()
        );
    }

    public PublicSurveyDto toPublicSurveyDto(Survey survey) {
        List<PublicSurveyPillarDto> pillarDtos = survey.getPillars() != null
                ? survey.getPillars().stream()
                        .sorted(Comparator.comparingInt(SurveyPillar::getDisplayOrder))
                        .map(this::toPublicPillarDto)
                        .toList()
                : List.of();
        return new PublicSurveyDto(
                survey.getId(),
                survey.getName(),
                survey.getDescription(),
                survey.getRespondentEmailMode(),
                survey.getRespondentNameMode(),
                pillarDtos
        );
    }

    /**
     * Build the authenticated post-assessment survey payload. Reuses the
     * pillar/question sub-DTOs from the public mapper — only the top-level
     * envelope differs.
     */
    public MemberSurveyDto toMemberSurveyDto(Survey survey) {
        List<PublicSurveyPillarDto> pillarDtos = survey.getPillars() != null
                ? survey.getPillars().stream()
                        .sorted(Comparator.comparingInt(SurveyPillar::getDisplayOrder))
                        .map(this::toPublicPillarDto)
                        .toList()
                : List.of();
        return new MemberSurveyDto(
                survey.getId(),
                survey.getName(),
                survey.getDescription(),
                pillarDtos
        );
    }

    private PublicSurveyPillarDto toPublicPillarDto(SurveyPillar pillar) {
        List<PublicSurveyQuestionDto> questionDtos = pillar.getQuestions() != null
                ? pillar.getQuestions().stream()
                        .sorted(Comparator.comparingInt(SurveyQuestion::getDisplayOrder))
                        .map(this::toPublicQuestionDto)
                        .toList()
                : List.of();
        return new PublicSurveyPillarDto(
                pillar.getId(),
                pillar.getName(),
                pillar.getDescription(),
                pillar.getDisplayOrder(),
                questionDtos
        );
    }

    private PublicSurveyQuestionDto toPublicQuestionDto(SurveyQuestion q) {
        return new PublicSurveyQuestionDto(
                q.getId(),
                q.getType(),
                q.getPromptText(),
                q.getDisplayOrder(),
                q.isRequired(),
                q.getConfigJson()
        );
    }
}
