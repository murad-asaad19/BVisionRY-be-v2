package com.bvisionry.reporting.service;

import com.bvisionry.assessment.AnswerRepository;
import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.pdf.PdfRenderer;
import com.bvisionry.evaluation.OverallSummaryRepository;
import com.bvisionry.evaluation.PillarEvaluationRepository;
import com.bvisionry.evaluation.entity.OverallSummary;
import com.bvisionry.evaluation.entity.PillarEvaluation;
import com.bvisionry.pipeline.SystemQuestion;
import com.bvisionry.reporting.dto.PersonalInfoEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
 * Builds the Team Insights PDF: an aggregated team summary followed by per-member
 * detail sections. Mirrors the data sourcing of {@link TeamInsightsExcelService}
 * but renders through Thymeleaf + ITextRenderer using the {@code team-insights-report}
 * template.
 *
 * <p>{@code memberIds}, when non-empty, restricts both the per-member sections
 * and the pillar averages to that subset. Aggregate counts (total assignments,
 * completion rate, gender breakdown) always reflect the full pipeline roster
 * so the rate is meaningful regardless of who was selected.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TeamInsightsPdfService {

    private final SubmissionRepository submissionRepository;
    private final PillarEvaluationRepository pillarEvaluationRepository;
    private final OverallSummaryRepository overallSummaryRepository;
    private final AnswerRepository answerRepository;
    private final MemberIdentityFactory memberIdentityFactory;
    private final PersonalInfoResolver personalInfoResolver;
    private final PdfRenderer pdfRenderer;

    @Transactional(readOnly = true)
    public byte[] generateReport(UUID orgId, UUID pipelineId, List<UUID> memberIds, boolean showNames) {
        Set<UUID> memberFilter = memberIds == null ? Set.of() : new HashSet<>(memberIds);

        List<Submission> rosterSubmissions = submissionRepository.findByOrgAndPipeline(orgId, pipelineId);

        // When the admin scoped the export to a subset, every aggregate (total
        // assignments, completion rate, gender breakdown) should describe that
        // subset only — otherwise a 1-of-10 export still reports "10 total".
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
                        .filter(e -> evaluatedIds.contains(e.getSubmission().getId()))
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

        MemberIdentity identity = memberIdentityFactory.identityFor(evaluatedSubmissions, showNames);

        // Personal-pillar answers per member — these are the demographic / "general
        // information" fields the user filled in on the assessment (job title,
        // years of experience, etc. plus the system FIRST_NAME / LAST_NAME / GENDER).
        // Surfaced per-member so the PDF reader sees who each subject is at a glance.
        // When names are hidden this block is exactly what would re-identify a
        // member, so it is suppressed entirely (empty map → the template's
        // not-empty guard hides the "Personal Information" section per member).
        Map<UUID, List<PersonalInfoEntry>> personalAnswersBySubmission = showNames
                ? personalInfoResolver.resolveBatch(evaluatedIds)
                : Map.of();

        Context ctx = new Context();
        ctx.setVariable("pipelineName", pipelineName.isBlank() ? "Pipeline" : pipelineName);
        ctx.setVariable("reportDate", LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM yyyy")));
        ctx.setVariable("totalAssignments", allSubmissions.size());
        ctx.setVariable("evaluatedCount", evaluatedSubmissions.size());
        ctx.setVariable("submittedCount", countByStatus(allSubmissions, SubmissionStatus.SUBMITTED));
        ctx.setVariable("inProgressCount", countByStatus(allSubmissions, SubmissionStatus.IN_PROGRESS));
        ctx.setVariable("failedCount", countByStatus(allSubmissions, SubmissionStatus.FAILED));
        ctx.setVariable("completionRate", TeamInsightsFormatter.formatRate(
                evaluatedSubmissions.size(), allSubmissions.size()));
        ctx.setVariable("avgOverallScore", TeamInsightsFormatter.averagePercent(
                summaryBySubmission.values().stream()
                        .map(OverallSummary::getOverallScorePercentage)
                        .toList()));
        ctx.setVariable("memberFilterApplied", !memberFilter.isEmpty());
        ctx.setVariable("genderBreakdown", buildGenderBreakdown(allSubmissions, genderBySubmission));
        ctx.setVariable("pillarAverages",
                buildPillarAverages(evaluatedSubmissions, evaluationsBySubmission, pillarOrder));
        ctx.setVariable("members",
                buildMemberSections(evaluatedSubmissions, summaryBySubmission, evaluationsBySubmission,
                        genderBySubmission, personalAnswersBySubmission, identity));

        byte[] pdf = pdfRenderer.renderTemplate("team-insights-report", ctx);
        log.info("Generated team insights PDF for org {} pipeline {} ({} members, {} bytes)",
                orgId, pipelineId, evaluatedSubmissions.size(), pdf.length);
        return pdf;
    }

    private List<Map<String, Object>> buildPillarAverages(
            List<Submission> evaluatedSubmissions,
            Map<UUID, List<PillarEvaluation>> evalsBySubmission,
            Map<UUID, String> pillarOrder) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<UUID, String> pillar : pillarOrder.entrySet()) {
            List<PillarEvaluation> pillarEvals = evaluatedSubmissions.stream()
                    .flatMap(sub -> evalsBySubmission.getOrDefault(sub.getId(), List.of()).stream())
                    .filter(e -> e.getPillar().getId().equals(pillar.getKey()))
                    .toList();
            List<BigDecimal> scores = pillarEvals.stream()
                    .map(PillarEvaluation::getScorePercentage)
                    .toList();
            Map<String, Long> maturity = pillarEvals.stream()
                    .collect(Collectors.groupingBy(
                            PillarEvaluation::getMaturityLabel,
                            TreeMap::new,
                            Collectors.counting()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pillarName", pillar.getValue());
            row.put("avgScore", TeamInsightsFormatter.averagePercent(scores));
            row.put("maturityCounts", maturity);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> buildMemberSections(
            List<Submission> evaluatedSubmissions,
            Map<UUID, OverallSummary> summaryBySubmission,
            Map<UUID, List<PillarEvaluation>> evalsBySubmission,
            Map<UUID, String> genderBySubmission,
            Map<UUID, List<PersonalInfoEntry>> personalAnswersBySubmission,
            MemberIdentity identity) {
        List<Map<String, Object>> sections = new ArrayList<>();
        for (Submission sub : evaluatedSubmissions) {
            OverallSummary summary = summaryBySubmission.get(sub.getId());
            NarrativeRedactor redactor = identity.redactor(sub);
            List<PillarEvaluation> evals = evalsBySubmission.getOrDefault(sub.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(e -> e.getPillar().getName(), String.CASE_INSENSITIVE_ORDER))
                    .toList();

            List<Map<String, Object>> pillars = new ArrayList<>();
            for (PillarEvaluation eval : evals) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("pillarName", eval.getPillar().getName());
                p.put("score", TeamInsightsFormatter.wholePercent(eval.getScorePercentage()));
                p.put("maturityLabel", eval.getMaturityLabel() == null ? "" : eval.getMaturityLabel());
                p.put("whatsWorking", eval.getAiWhatsWorking() == null ? List.of() : redactor.redact(eval.getAiWhatsWorking()));
                p.put("whatCanImprove", eval.getAiWhatCanImprove() == null ? List.of() : redactor.redact(eval.getAiWhatCanImprove()));
                pillars.add(p);
            }

            Map<String, Object> section = new LinkedHashMap<>();
            section.put("name", identity.name(sub));
            section.put("email", identity.email(sub));
            section.put("userType", userTypeLabel(sub));
            section.put("gender", genderBySubmission.getOrDefault(sub.getId(), ""));
            section.put("evaluatedAt", sub.getEvaluatedAt());
            section.put("overallScore", summary == null ? "—" : TeamInsightsFormatter.wholePercent(summary.getOverallScorePercentage()));
            section.put("summaryNarrative", summary == null || summary.getSummaryNarrative() == null
                    ? "" : redactor.redact(summary.getSummaryNarrative()));
            section.put("strengths", summary == null || summary.getStrengths() == null ? List.of() : redactor.redact(summary.getStrengths()));
            section.put("developmentAreas", summary == null || summary.getDevelopmentAreas() == null ? List.of() : redactor.redact(summary.getDevelopmentAreas()));
            section.put("personalInfo", personalAnswersBySubmission.getOrDefault(sub.getId(), List.of()));
            section.put("pillars", pillars);
            sections.add(section);
        }
        return sections;
    }

    private String userTypeLabel(Submission sub) {
        if (sub.getUser() == null) return "—";
        String type = sub.getUser().getUserType();
        return type == null || type.isBlank() ? "—" : type;
    }

    private Map<String, Object> buildGenderBreakdown(
            List<Submission> allSubmissions,
            Map<UUID, String> genderBySubmission) {
        Map<String, Long> counts = new TreeMap<>();
        for (Submission sub : allSubmissions) {
            counts.merge(genderBySubmission.getOrDefault(sub.getId(), "Unspecified"), 1L, Long::sum);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("counts", counts);
        result.put("total", allSubmissions.size());
        return result;
    }

    private long countByStatus(List<Submission> submissions, SubmissionStatus status) {
        return submissions.stream().filter(s -> s.getStatus() == status).count();
    }
}
