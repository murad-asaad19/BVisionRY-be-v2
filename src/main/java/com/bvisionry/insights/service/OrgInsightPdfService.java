package com.bvisionry.insights.service;

import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.pdf.PdfRenderer;
import com.bvisionry.insights.InsightReportRepository;
import com.bvisionry.insights.entity.InsightReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrgInsightPdfService {

    private final InsightReportRepository insightReportRepository;
    private final SubmissionRepository submissionRepository;
    private final PdfRenderer pdfRenderer;

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public byte[] generatePdf(UUID orgId, UUID reportId, String orgName, boolean showNames) {
        InsightReport report = insightReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("InsightReport", reportId.toString()));
        // Cross-org access guard — uniform 404 (not 403) so the existence of a
        // foreign-org report cannot be probed.
        if (report.getOrganization() == null
                || !report.getOrganization().getId().equals(orgId)) {
            throw new ResourceNotFoundException("InsightReport", reportId.toString());
        }
        report.assertReadyForExport();
        Map<String, Object> data = report.getReportJson();
        Map<String, Object> teamThemes = safeCast(data.get("teamThemes"));

        Context ctx = new Context();
        ctx.setVariable("orgName", orgName);
        ctx.setVariable("pipelineName", report.getPipeline() != null ? report.getPipeline().getName() : "All Pipelines");
        ctx.setVariable("reportDate", LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM yyyy")));
        ctx.setVariable("generatedAt", report.getGeneratedAt().toString());
        ctx.setVariable("aiModelUsed", report.getAiModelUsed());

        // Team themes
        ctx.setVariable("strengths", teamThemes != null ? safeList(teamThemes.get("commonStrengths")) : List.of());
        ctx.setVariable("weaknesses", teamThemes != null ? safeList(teamThemes.get("commonWeaknesses")) : List.of());
        ctx.setVariable("patterns", teamThemes != null ? safeList(teamThemes.get("patterns")) : List.of());
        ctx.setVariable("recommendations", teamThemes != null ? safeList(teamThemes.get("recommendations")) : List.of());

        // Coaching
        List<?> coachingList = data.get("individualCoaching") instanceof List<?> cl ? cl : List.of();
        ctx.setVariable("coaching", coachingList);

        // Resolve member names if showNames is true. Honour the memberIds filter
        // captured at generation time so a report built from a subset cannot
        // accidentally pull names of unrelated members into the export.
        ctx.setVariable("showNames", showNames);
        if (showNames) {
            List<String> memberNames = new ArrayList<>();
            List<Submission> submissions = resolveMemberSubmissions(report);
            for (int i = 0; i < coachingList.size(); i++) {
                if (i < submissions.size()) {
                    memberNames.add(submissions.get(i).getUser().getName());
                } else {
                    memberNames.add("Member " + (i + 1));
                }
            }
            ctx.setVariable("memberNames", memberNames);
        }

        // Benchmarking
        Map<String, Object> benchmarking = safeCast(data.get("benchmarking"));
        ctx.setVariable("benchmarkComparison", benchmarking != null ? benchmarking.get("teamVsPlatformComparison") : null);
        ctx.setVariable("outlierPillars", benchmarking != null ? safeList(benchmarking.get("outlierPillars")) : List.of());

        // Raw fallback
        ctx.setVariable("rawResponse", data.get("rawResponse"));

        byte[] pdf = pdfRenderer.renderTemplate("org-insights-report", ctx);
        log.info("Generated org insight PDF for report {} ({} bytes)", reportId, pdf.length);
        return pdf;
    }

    /**
     * Evaluated submissions tied to this report — restricted to {@code report.getMemberIds()}
     * if a member filter was applied at generation, otherwise all evaluated submissions.
     *
     * <p>Sort is by user.id: the AI input was assembled in the same order in
     * {@link InsightService#buildAnonymizedData}, so masked positional labels
     * ("Member 1", "Member 2", ...) line up between the AI's coachingList and
     * the export. PDF and Excel use the same ordering for cross-export
     * consistency.
     */
    private List<Submission> resolveMemberSubmissions(InsightReport report) {
        if (report.getPipeline() == null || report.getOrganization() == null) {
            return List.of();
        }
        Set<UUID> memberFilter = report.getMemberIds();
        return submissionRepository.findByOrgAndPipeline(
                        report.getOrganization().getId(), report.getPipeline().getId())
                .stream()
                .filter(s -> s.getStatus() == SubmissionStatus.EVALUATED)
                .filter(s -> memberFilter == null || memberFilter.isEmpty()
                        || memberFilter.contains(s.getUser().getId()))
                .sorted(java.util.Comparator.comparing(s -> s.getUser().getId()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeCast(Object obj) {
        return obj instanceof Map ? (Map<String, Object>) obj : null;
    }

    @SuppressWarnings("unchecked")
    private List<Object> safeList(Object obj) {
        return obj instanceof List ? (List<Object>) obj : List.of();
    }
}
