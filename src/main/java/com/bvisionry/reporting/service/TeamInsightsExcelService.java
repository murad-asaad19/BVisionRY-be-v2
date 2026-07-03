package com.bvisionry.reporting.service;

import com.bvisionry.assessment.AnswerRepository;
import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.entity.Answer;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.common.enums.PillarType;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.excel.ExcelWorkbookBuilder;
import com.bvisionry.evaluation.OverallSummaryRepository;
import com.bvisionry.evaluation.PillarEvaluationRepository;
import com.bvisionry.evaluation.entity.OverallSummary;
import com.bvisionry.evaluation.entity.PillarEvaluation;
import com.bvisionry.pipeline.SystemQuestion;
import com.bvisionry.pipeline.entity.Question;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds a multi-sheet workbook for Team Insights export:
 *   - Overview       -- aggregate stats (completion rate, gender %, averages)
 *   - Summary        -- per-member scores, one column per pillar, overall last
 *   - Profiles       -- demographic info per member (Personal pillar answers)
 *   - Highlights     -- per member: summary, strengths, development areas
 *   - Pillar Details -- long-format per-member × per-pillar narrative
 *
 * All per-member sheets are limited to EVALUATED submissions. Aggregate counts
 * on the Overview sheet use every submission (any status) so completion rate
 * reflects the full pipeline roster.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamInsightsExcelService {

    private final SubmissionRepository submissionRepository;
    private final PillarEvaluationRepository pillarEvaluationRepository;
    private final OverallSummaryRepository overallSummaryRepository;
    private final AnswerRepository answerRepository;
    private final MemberIdentityFactory memberIdentityFactory;

    /**
     * Backwards-compatible overload — exports every evaluated submission in the
     * pipeline. Prefer the {@link #generateReport(UUID, UUID, List, boolean)}
     * variant so callers can scope the export to a specific subset of members.
     */
    public byte[] generateReport(UUID orgId, UUID pipelineId, boolean showNames) {
        return generateReport(orgId, pipelineId, null, showNames);
    }

    /**
     * Build the workbook restricted to the given {@code memberIds}. A null or
     * empty list means "include every evaluated member" — the legacy behaviour.
     * When a filter is supplied the aggregate counts on the Overview sheet are
     * scoped to those members too, so totals/completion rate/gender breakdown
     * describe the actual subset being exported (not the full pipeline roster).
     */
    public byte[] generateReport(UUID orgId, UUID pipelineId, List<UUID> memberIds, boolean showNames) {
        Set<UUID> memberFilter = memberIds == null ? Set.of() : new HashSet<>(memberIds);
        List<Submission> rosterSubmissions = submissionRepository.findByOrgAndPipeline(orgId, pipelineId);

        // When a member filter is set, every aggregate in the report — totals,
        // completion rate, gender breakdown — should reflect only those members.
        // Otherwise the "1 of 2 selected" export still showed "2 total assignments".
        List<Submission> allSubmissions = memberFilter.isEmpty()
                ? rosterSubmissions
                : rosterSubmissions.stream()
                        .filter(s -> memberFilter.contains(s.getUser().getId()))
                        .toList();

        // Tie-break on user.id so two members sharing a name get a stable
        // position across exports — otherwise the JVM's stable sort falls back
        // to physical row order, which Postgres does not guarantee.
        List<Submission> evaluatedSubmissions = allSubmissions.stream()
                .filter(s -> s.getStatus() == SubmissionStatus.EVALUATED)
                .sorted(Comparator
                        .comparing((Submission s) -> s.getUser().getName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(s -> s.getUser().getId()))
                .toList();

        List<UUID> evaluatedIds = evaluatedSubmissions.stream().map(Submission::getId).toList();

        Map<UUID, OverallSummary> summaryBySubmission = evaluatedIds.isEmpty()
                ? Map.of()
                : overallSummaryRepository.findBySubmissionIdIn(evaluatedIds).stream()
                        .collect(Collectors.toMap(s -> s.getSubmission().getId(), s -> s));

        Map<UUID, List<PillarEvaluation>> evaluationsBySubmission =
                pillarEvaluationRepository.findByOrgAndPipeline(orgId, pipelineId).stream()
                        .collect(Collectors.groupingBy(e -> e.getSubmission().getId()));

        String pipelineName = allSubmissions.isEmpty() ? ""
                : allSubmissions.getFirst().getAssignment().getPipeline().getName();

        Map<UUID, String> pillarOrder = TeamInsightsFormatter.buildPillarOrder(evaluationsBySubmission);

        List<UUID> allSubmissionIds = allSubmissions.stream().map(Submission::getId).toList();
        Map<UUID, String> genderBySubmission = allSubmissionIds.isEmpty()
                ? Map.of()
                : answerRepository.findBySubmissionIdsAndSystemKey(allSubmissionIds, SystemQuestion.GENDER).stream()
                        .collect(Collectors.toMap(
                                a -> a.getSubmission().getId(),
                                AssessmentAnswerFormatter::answerLabel,
                                (a, b) -> a));

        // When showNames is false, the same submission renders as "Member N" everywhere,
        // its email is blanked, and its name is scrubbed from every narrative. All three
        // go through MemberIdentity so callers never touch sub.getUser() directly and
        // can't accidentally leak a real name.
        MemberIdentity identity = memberIdentityFactory.identityFor(evaluatedSubmissions, showNames);

        try (ExcelWorkbookBuilder wb = new ExcelWorkbookBuilder();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            writeOverviewSheet(wb, pipelineName, allSubmissions, evaluatedSubmissions,
                    summaryBySubmission, evaluationsBySubmission, pillarOrder, genderBySubmission);
            writeSummarySheet(wb, evaluatedSubmissions, summaryBySubmission,
                    evaluationsBySubmission, pillarOrder, genderBySubmission, identity);
            // The Profiles sheet exists solely to surface who each member is
            // (their personal-pillar answers: name, contact, DOB, …). That's
            // exactly the information anonymisation suppresses, so when names are
            // hidden the sheet is dropped entirely rather than leaking identity
            // through the answer columns.
            if (showNames) {
                writeProfilesSheet(wb, evaluatedSubmissions, genderBySubmission, identity);
            }
            writeHighlightsSheet(wb, evaluatedSubmissions, summaryBySubmission, identity);
            writePillarDetailsSheet(wb, evaluatedSubmissions, evaluationsBySubmission, identity);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate team insights Excel report", e);
        }
    }

    // Pillar ordering / rate / average helpers live in TeamInsightsFormatter so
    // the PDF export renders the same numbers and the same column order.

    // Answer formatting (answerLabel / formatAnswer / buildQuestionHeader) moved
    // to AssessmentAnswerFormatter so the PDF export can render the same labels.

    private void writeOverviewSheet(ExcelWorkbookBuilder wb,
                                    String pipelineName,
                                    List<Submission> allSubmissions,
                                    List<Submission> evaluatedSubmissions,
                                    Map<UUID, OverallSummary> summaryBySubmission,
                                    Map<UUID, List<PillarEvaluation>> evalsBySubmission,
                                    Map<UUID, String> pillarOrder,
                                    Map<UUID, String> genderBySubmission) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Overview");
        s.headers("Metric", "Value");

        int total = allSubmissions.size();
        int evaluated = evaluatedSubmissions.size();
        int inProgress = (int) allSubmissions.stream()
                .filter(x -> x.getStatus() == SubmissionStatus.IN_PROGRESS).count();
        int submitted = (int) allSubmissions.stream()
                .filter(x -> x.getStatus() == SubmissionStatus.SUBMITTED).count();
        int failed = (int) allSubmissions.stream()
                .filter(x -> x.getStatus() == SubmissionStatus.FAILED).count();

        s.labeledRow("Pipeline", pipelineName);
        s.labeledRow("Total assignments", total);
        s.labeledRow("Completed (evaluated)", evaluated);
        s.labeledRow("Submitted (awaiting evaluation)", submitted);
        s.labeledRow("In progress", inProgress);
        if (failed > 0) s.labeledRow("Failed", failed);
        s.labeledRow("Completion rate", TeamInsightsFormatter.formatRate(evaluated, total));

        s.blankRow();
        s.labeledRow("Average overall score",
                TeamInsightsFormatter.averagePercent(summaryBySubmission.values().stream()
                        .map(OverallSummary::getOverallScorePercentage)
                        .toList()));

        // Gender breakdown across the whole roster (any status) so we can track
        // demographics of the population assigned to the pipeline, not just the
        // subset that finished.
        s.blankRow();
        Map<String, Long> genderCounts = new TreeMap<>();
        for (Submission sub : allSubmissions) {
            String gender = genderBySubmission.getOrDefault(sub.getId(), "Unspecified");
            genderCounts.merge(gender, 1L, Long::sum);
        }
        for (Map.Entry<String, Long> entry : genderCounts.entrySet()) {
            s.labeledRow("Gender — " + entry.getKey(),
                    entry.getValue() + " (" + TeamInsightsFormatter.formatRate(entry.getValue().intValue(), total) + ")");
        }

        // Pillar averages (evaluated submissions only — a not-yet-evaluated
        // submission has no pillar scores to average).
        s.blankRow();
        for (Map.Entry<UUID, String> pillar : pillarOrder.entrySet()) {
            List<BigDecimal> scores = evaluatedSubmissions.stream()
                    .flatMap(sub -> evalsBySubmission.getOrDefault(sub.getId(), List.of()).stream())
                    .filter(e -> e.getPillar().getId().equals(pillar.getKey()))
                    .map(PillarEvaluation::getScorePercentage)
                    .toList();
            s.labeledRow("Avg — " + pillar.getValue(), TeamInsightsFormatter.averagePercent(scores));
        }

        s.autoSize();
    }

    private void writeSummarySheet(ExcelWorkbookBuilder wb,
                                   List<Submission> submissions,
                                   Map<UUID, OverallSummary> summaryBySubmission,
                                   Map<UUID, List<PillarEvaluation>> evalsBySubmission,
                                   Map<UUID, String> pillarOrder,
                                   Map<UUID, String> genderBySubmission,
                                   MemberIdentity identity) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Summary");

        // Columns: Member, Email, Type, Gender, Evaluated At, <pillar columns...>, Overall Score
        int pillarCount = pillarOrder.size();
        int width = 5 + pillarCount + 1;
        Object[] header = new Object[width];
        header[0] = "Member";
        header[1] = "Email";
        header[2] = "Type";
        header[3] = "Gender";
        header[4] = "Evaluated At";
        int col = 5;
        for (String pillarName : pillarOrder.values()) {
            header[col++] = pillarName;
        }
        header[width - 1] = "Overall Score";
        s.headers(toStrings(header));

        if (submissions.isEmpty()) {
            Object[] empty = new Object[width];
            empty[0] = "No evaluated members";
            for (int i = 1; i < width; i++) empty[i] = "";
            s.row(empty);
            s.autoSize();
            return;
        }

        for (Submission sub : submissions) {
            OverallSummary summary = summaryBySubmission.get(sub.getId());
            Map<UUID, BigDecimal> scoresByPillar = evalsBySubmission
                    .getOrDefault(sub.getId(), List.of()).stream()
                    .collect(Collectors.toMap(
                            e -> e.getPillar().getId(),
                            PillarEvaluation::getScorePercentage,
                            (a, b) -> a));

            Object[] row = new Object[width];
            row[0] = identity.name(sub);
            row[1] = identity.email(sub);
            row[2] = userTypeLabel(sub);
            row[3] = genderBySubmission.getOrDefault(sub.getId(), "");
            row[4] = sub.getEvaluatedAt();
            int i = 5;
            for (UUID pillarId : pillarOrder.keySet()) {
                BigDecimal score = scoresByPillar.get(pillarId);
                row[i++] = score == null ? "" : ExcelWorkbookBuilder.formatPercent(score);
            }
            row[width - 1] = summary == null ? "" : ExcelWorkbookBuilder.formatPercent(summary.getOverallScorePercentage());
            s.row(row);
        }

        s.autoSize();
    }

    /**
     * "Profiles" sheet — one row per evaluated member with the demographic info
     * they submitted on the assessment (Personal pillar answers). Distinct from
     * the Summary / Q&amp;A sheets so admins can see "who these members are"
     * at a glance without scanning a 50-question grid.
     */
    private void writeProfilesSheet(ExcelWorkbookBuilder wb,
                                    List<Submission> submissions,
                                    Map<UUID, String> genderBySubmission,
                                    MemberIdentity identity) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Profiles");

        if (submissions.isEmpty()) {
            s.headers("Member");
            s.row("No evaluated members");
            s.autoSize();
            return;
        }

        List<UUID> submissionIds = submissions.stream().map(Submission::getId).toList();
        List<Answer> personalAnswers = answerRepository
                .findBySubmissionIdsWithQuestionAndPillar(submissionIds).stream()
                .filter(a -> a.getQuestion().getPillar().getType() == PillarType.PERSONAL)
                .toList();

        // Stable column order — pillar.displayOrder, question.displayOrder. Same
        // ordering rule the Q&A sheet uses, so the two sheets stay aligned when
        // the admin compares them side-by-side.
        Map<UUID, Question> personalQuestions = new LinkedHashMap<>();
        personalAnswers.stream()
                .map(Answer::getQuestion)
                .sorted(Comparator
                        .comparingInt((Question q) -> q.getPillar().getDisplayOrder())
                        .thenComparingInt(Question::getDisplayOrder)
                        .thenComparing(q -> q.getId().toString()))
                .forEach(q -> personalQuestions.putIfAbsent(q.getId(), q));

        // Member, Email, Type, Gender, then dynamic personal questions.
        int width = 4 + personalQuestions.size();
        Object[] header = new Object[width];
        header[0] = "Member";
        header[1] = "Email";
        header[2] = "Type";
        header[3] = "Gender";
        int h = 4;
        for (Question q : personalQuestions.values()) {
            header[h++] = AssessmentAnswerFormatter.buildQuestionHeader(q);
        }
        s.headers(toStrings(header));

        Map<UUID, Map<UUID, Answer>> answersBySubmission = personalAnswers.stream()
                .collect(Collectors.groupingBy(
                        a -> a.getSubmission().getId(),
                        Collectors.toMap(
                                a -> a.getQuestion().getId(),
                                a -> a,
                                (a, b) -> a)));

        for (Submission sub : submissions) {
            Map<UUID, Answer> byQuestion = answersBySubmission.getOrDefault(sub.getId(), Map.of());
            Object[] row = new Object[width];
            row[0] = identity.name(sub);
            row[1] = identity.email(sub);
            row[2] = userTypeLabel(sub);
            row[3] = genderBySubmission.getOrDefault(sub.getId(), "");
            int i = 4;
            for (Map.Entry<UUID, Question> entry : personalQuestions.entrySet()) {
                Answer ans = byQuestion.get(entry.getKey());
                row[i++] = ans == null ? "" : AssessmentAnswerFormatter.formatAnswer(entry.getValue(), ans);
            }
            s.row(row);
        }

        s.autoSize();
    }

    /** Falls back to "—" so an empty cell never confuses the reader. */
    private String userTypeLabel(Submission sub) {
        if (sub.getUser() == null) return "—";
        String type = sub.getUser().getUserType();
        return type == null || type.isBlank() ? "—" : type;
    }

    private void writeHighlightsSheet(ExcelWorkbookBuilder wb,
                                      List<Submission> submissions,
                                      Map<UUID, OverallSummary> summaryBySubmission,
                                      MemberIdentity identity) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Highlights");
        s.headers("Member", "Summary", "Strengths", "Development areas");
        for (Submission sub : submissions) {
            OverallSummary summary = summaryBySubmission.get(sub.getId());
            if (summary == null) {
                s.row(identity.name(sub), "", "", "");
                continue;
            }
            NarrativeRedactor redactor = identity.redactor(sub);
            s.row(
                    identity.name(sub),
                    redactor.redact(summary.getSummaryNarrative()),
                    ExcelWorkbookBuilder.bullets(redactor.redact(summary.getStrengths())),
                    ExcelWorkbookBuilder.bullets(redactor.redact(summary.getDevelopmentAreas()))
            );
        }
        s.autoSize();
    }

    private void writePillarDetailsSheet(ExcelWorkbookBuilder wb,
                                         List<Submission> submissions,
                                         Map<UUID, List<PillarEvaluation>> evalsBySubmission,
                                         MemberIdentity identity) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Pillar Details");
        s.headers("Member", "Pillar", "Score", "Maturity", "What's working", "What can improve");
        for (Submission sub : submissions) {
            NarrativeRedactor redactor = identity.redactor(sub);
            List<PillarEvaluation> evals = evalsBySubmission.getOrDefault(sub.getId(), List.of());
            evals.stream()
                    .sorted(Comparator.comparing(e -> e.getPillar().getName(), String.CASE_INSENSITIVE_ORDER))
                    .forEach(eval -> s.row(
                            identity.name(sub),
                            eval.getPillar().getName(),
                            ExcelWorkbookBuilder.formatPercent(eval.getScorePercentage()),
                            eval.getMaturityLabel(),
                            ExcelWorkbookBuilder.bullets(redactor.redact(eval.getAiWhatsWorking())),
                            ExcelWorkbookBuilder.bullets(redactor.redact(eval.getAiWhatCanImprove()))
                    ));
        }
        s.autoSize();
    }

    private String[] toStrings(Object[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i] == null ? "" : values[i].toString();
        }
        return out;
    }
}
