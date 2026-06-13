package com.bvisionry.reporting.service;

import com.bvisionry.assessment.AnswerRepository;
import com.bvisionry.assessment.entity.Answer;
import com.bvisionry.common.enums.PillarType;
import com.bvisionry.pipeline.entity.Question;
import com.bvisionry.reporting.dto.PersonalInfoEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reads the Personal-pillar ("general information") answers for one or more
 * submissions and renders them as ordered {@link PersonalInfoEntry} rows.
 *
 * <p>Entries are sorted by pillar display order, then question display order,
 * so every surface (assessment side drawer, member PDF/Excel, team insights
 * PDF) shows the fields in the same sequence the assessor saw on screen.
 * Blank answers are dropped so the UI never shows label rows with empty values.
 */
@Service
@RequiredArgsConstructor
public class PersonalInfoResolver {

    private final AnswerRepository answerRepository;

    /** Single-submission convenience wrapper around {@link #resolveBatch}. */
    public List<PersonalInfoEntry> resolve(UUID submissionId) {
        return resolveBatch(List.of(submissionId)).getOrDefault(submissionId, List.of());
    }

    /**
     * Batched fetch — issues one query for all submissions, groups answers by
     * submission, and renders each in display order. Used by the team insights
     * PDF where the per-member loop would otherwise N+1 the answer table.
     */
    public Map<UUID, List<PersonalInfoEntry>> resolveBatch(List<UUID> submissionIds) {
        if (submissionIds == null || submissionIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, List<Answer>> bySubmission = answerRepository
                .findBySubmissionIdsWithQuestionAndPillar(submissionIds).stream()
                .filter(a -> a.getQuestion().getPillar().getType() == PillarType.PERSONAL)
                .collect(Collectors.groupingBy(a -> a.getSubmission().getId()));

        Comparator<Answer> displayOrder = Comparator
                .comparingInt((Answer a) -> a.getQuestion().getPillar().getDisplayOrder())
                .thenComparingInt(a -> a.getQuestion().getDisplayOrder())
                .thenComparing(a -> a.getQuestion().getId().toString());

        Map<UUID, List<PersonalInfoEntry>> result = new LinkedHashMap<>();
        for (UUID submissionId : submissionIds) {
            List<Answer> own = bySubmission.getOrDefault(submissionId, List.of()).stream()
                    .sorted(displayOrder)
                    .toList();
            List<PersonalInfoEntry> rendered = new ArrayList<>();
            for (Answer a : own) {
                Question q = a.getQuestion();
                String value = AssessmentAnswerFormatter.formatAnswer(q, a);
                if (value == null || value.isBlank()) continue;
                rendered.add(new PersonalInfoEntry(q.getPromptText(), value));
            }
            result.put(submissionId, rendered);
        }
        return result;
    }
}
