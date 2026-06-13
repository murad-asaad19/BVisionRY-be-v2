package com.bvisionry.reporting.service;

import com.bvisionry.assessment.AnswerRepository;
import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.entity.Answer;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.pipeline.SystemQuestion;
import com.bvisionry.pipeline.entity.Question;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Resolves the participant display name for member-facing reports
 * (PDF, Excel, etc.) from authoritative server-side state. Mirrors
 * {@code EvaluationService.resolveMemberDisplayName}: prefers the
 * FIRST_NAME personal-pillar answer (because account names are often
 * handles like "Test Member") and falls back to the user account's
 * display name.
 *
 * <p>This exists so the controller does not accept a client-supplied
 * {@code participantName} — that param previously let any user mint a
 * report bearing someone else's name.
 */
@Component
@RequiredArgsConstructor
public class MemberDisplayNameResolver {

    private static final String ANONYMOUS_LABEL = "Member";

    private final SubmissionRepository submissionRepository;
    private final AnswerRepository answerRepository;

    /**
     * @param submissionId submission whose owner's display name to resolve
     * @param showNames if false, returns the redacted "Member" label and skips
     *                  the FIRST_NAME lookup entirely
     */
    @Transactional(readOnly = true)
    public String resolve(UUID submissionId, boolean showNames) {
        if (!showNames) return ANONYMOUS_LABEL;
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission", submissionId.toString()));
        List<Answer> answers = answerRepository.findBySubmissionIdWithQuestionAndPillar(submissionId);
        for (Answer a : answers) {
            Question q = a.getQuestion();
            if (q == null || !SystemQuestion.FIRST_NAME.equals(q.getSystemKey())) continue;
            String text = a.getResponseText();
            if (text != null && !text.isBlank()) return text.trim();
        }
        if (submission.getUser() != null) {
            return submission.getUser().getName();
        }
        // Public (anonymous) submissions have no account — fall back to the
        // respondent name captured at session start, else a neutral label.
        String respondentName = submission.getRespondentName();
        return respondentName != null && !respondentName.isBlank()
                ? respondentName.trim()
                : "Anonymous";
    }
}
