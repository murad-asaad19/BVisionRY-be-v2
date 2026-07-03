package com.bvisionry.reporting.service;

import com.bvisionry.assessment.AnswerRepository;
import com.bvisionry.assessment.entity.Answer;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.pipeline.SystemQuestion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds the {@link MemberIdentity} for a team export. When names are hidden it
 * also loads the FIRST_NAME / LAST_NAME answers the per-member narrative
 * redactors need — the AI addresses members by name inside the prose, so masking
 * the identity columns alone would still leak who they are.
 *
 * <p>Owns that wiring so every export format gets identical masking from a
 * single call instead of each service assembling the name lookups itself.
 */
@Component
@RequiredArgsConstructor
public class MemberIdentityFactory {

    private final AnswerRepository answerRepository;

    public MemberIdentity identityFor(List<Submission> submissions, boolean showNames) {
        if (showNames || submissions.isEmpty()) {
            return MemberIdentity.of(submissions, showNames, Map.of(), Map.of());
        }
        List<UUID> submissionIds = submissions.stream().map(Submission::getId).toList();
        List<Answer> nameAnswers = answerRepository.findBySubmissionIdsAndSystemKeys(
                submissionIds, List.of(SystemQuestion.FIRST_NAME, SystemQuestion.LAST_NAME));
        return MemberIdentity.of(submissions, false,
                answersFor(nameAnswers, SystemQuestion.FIRST_NAME),
                answersFor(nameAnswers, SystemQuestion.LAST_NAME));
    }

    /** submissionId → answer text for one system question, from the batched rows. */
    private static Map<UUID, String> answersFor(List<Answer> answers, String systemKey) {
        return answers.stream()
                .filter(a -> systemKey.equals(a.getQuestion().getSystemKey()))
                .collect(Collectors.toMap(
                        a -> a.getSubmission().getId(),
                        AssessmentAnswerFormatter::answerLabel,
                        (a, b) -> a));
    }
}
