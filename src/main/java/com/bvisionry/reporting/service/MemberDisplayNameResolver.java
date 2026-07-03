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
 * Resolves the participant identity for member-facing reports (PDF, Excel, etc.)
 * from authoritative server-side state: the display name shown on the cover plus
 * the {@link NarrativeRedactor} that scrubs the member's name(s) out of the AI
 * narratives when names are hidden — the AI addresses the member by name inside
 * the prose ("Murad, your physical hardware…"), so masking the label alone would
 * still leak it.
 *
 * <p>Name preference mirrors {@code EvaluationService.resolveMemberDisplayName}:
 * the FIRST_NAME personal-pillar answer (because account names are often handles
 * like "Test Member") falling back to the user account's display name.
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

    /** Display name + narrative redactor for one member report. */
    public record ReportIdentity(String displayName, NarrativeRedactor redactor) {}

    /**
     * @param submissionId submission whose owner's identity to resolve
     * @param showNames if false, the display name is the redacted "Member" label
     *                  and the redactor scrubs the member's name(s) from narratives
     */
    @Transactional(readOnly = true)
    public ReportIdentity resolveIdentity(UUID submissionId, boolean showNames) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission", submissionId.toString()));
        List<Answer> answers = answerRepository.findBySubmissionIdWithQuestionAndPillar(submissionId);
        String first = systemAnswer(answers, SystemQuestion.FIRST_NAME);
        String accountName = submission.getUser() != null ? submission.getUser().getName() : null;
        String respondentName = submission.getRespondentName();

        if (!showNames) {
            NarrativeRedactor redactor = NarrativeRedactor.forMember(ANONYMOUS_LABEL,
                    first, systemAnswer(answers, SystemQuestion.LAST_NAME), accountName, respondentName);
            return new ReportIdentity(ANONYMOUS_LABEL, redactor);
        }

        String displayName;
        if (first != null) {
            displayName = first;
        } else if (accountName != null) {
            displayName = accountName;
        } else {
            // Public (anonymous) submissions have no account — fall back to the
            // respondent name captured at session start, else a neutral label.
            displayName = respondentName != null && !respondentName.isBlank()
                    ? respondentName.trim()
                    : "Anonymous";
        }
        return new ReportIdentity(displayName, NarrativeRedactor.disabled());
    }

    private String systemAnswer(List<Answer> answers, String systemKey) {
        for (Answer a : answers) {
            Question q = a.getQuestion();
            if (q == null || !systemKey.equals(q.getSystemKey())) continue;
            String text = a.getResponseText();
            if (text != null && !text.isBlank()) return text.trim();
        }
        return null;
    }
}
