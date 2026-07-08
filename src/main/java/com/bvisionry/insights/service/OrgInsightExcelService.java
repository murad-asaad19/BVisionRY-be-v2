package com.bvisionry.insights.service;

import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.excel.ExcelWorkbookBuilder;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.insights.InsightReportRepository;
import com.bvisionry.insights.entity.InsightReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrgInsightExcelService {

    private final InsightReportRepository insightReportRepository;
    private final SubmissionRepository submissionRepository;

    @Transactional(readOnly = true)
    public byte[] generate(UUID orgId, UUID reportId, String orgName, boolean showNames) {
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
        Map<String, Object> benchmarking = safeCast(data.get("benchmarking"));
        List<?> coachingList = data.get("individualCoaching") instanceof List<?> cl ? cl : List.of();

        List<String> memberNames = resolveMemberNames(report, coachingList.size(), showNames);

        try (ExcelWorkbookBuilder wb = new ExcelWorkbookBuilder();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            writeOverview(wb, orgName, report);
            writeTeamThemes(wb, teamThemes);
            writeRecommendations(wb, teamThemes);
            writeCoaching(wb, coachingList, memberNames);
            writeBenchmarking(wb, benchmarking);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate org insights Excel", e);
        }
    }

    private void writeOverview(ExcelWorkbookBuilder wb, String orgName, InsightReport report) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Overview");
        s.headers("Field", "Value");
        s.labeledRow("Organization", orgName);
        s.labeledRow("Pipeline",
                report.getPipeline() != null ? report.getPipeline().getName() : "All pipelines");
        s.labeledRow("Generated at", report.getGeneratedAt());
        s.labeledRow("AI model", report.getAiModelUsed());
        s.autoSize();
    }

    private void writeTeamThemes(ExcelWorkbookBuilder wb, Map<String, Object> themes) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Team Themes");
        List<Object> strengths = themes != null ? safeList(themes.get("commonStrengths")) : List.of();
        List<Object> weaknesses = themes != null ? growthEdges(themes) : List.of();
        List<Object> patterns = themes != null ? safeList(themes.get("patterns")) : List.of();

        s.headers("Common strengths", "Growth edges", "Recurring patterns");
        int rowCount = Math.max(strengths.size(), Math.max(weaknesses.size(), patterns.size()));
        for (int i = 0; i < rowCount; i++) {
            s.row(
                    i < strengths.size() ? String.valueOf(strengths.get(i)) : "",
                    i < weaknesses.size() ? String.valueOf(weaknesses.get(i)) : "",
                    i < patterns.size() ? String.valueOf(patterns.get(i)) : ""
            );
        }
        s.autoSize();
    }

    private void writeRecommendations(ExcelWorkbookBuilder wb, Map<String, Object> themes) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Recommendations");
        List<Object> recs = themes != null ? safeList(themes.get("recommendations")) : List.of();
        s.headers("Recommendation");
        for (Object r : recs) {
            s.row(String.valueOf(r));
        }
        s.autoSize();
    }

    private void writeCoaching(ExcelWorkbookBuilder wb, List<?> coaching, List<String> memberNames) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Individual Coaching");
        s.headers("Member", "Focus areas", "Suggested actions");
        for (int i = 0; i < coaching.size(); i++) {
            String name = i < memberNames.size() ? memberNames.get(i) : "Member " + (i + 1);
            Object entry = coaching.get(i);
            String focusAreas = "";
            String actions = "";
            if (entry instanceof Map<?, ?> map) {
                focusAreas = ExcelWorkbookBuilder.bullets(asList(map.get("focusAreas")));
                actions = ExcelWorkbookBuilder.bullets(asList(map.get("suggestedActions")));
            }
            s.row(name, focusAreas, actions);
        }
        s.autoSize();
    }

    private List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private void writeBenchmarking(ExcelWorkbookBuilder wb, Map<String, Object> benchmarking) {
        ExcelWorkbookBuilder.SheetBuilder s = wb.newSheet("Benchmarking");
        if (benchmarking == null) {
            s.headers("Field", "Value");
            s.labeledRow("Status", "No benchmarking data");
            s.autoSize();
            return;
        }
        s.headers("Field", "Value");
        Object comparison = benchmarking.get("teamVsPlatformComparison");
        if (comparison != null) {
            s.labeledRow("Team vs platform", comparison.toString());
        }
        List<Object> outliers = safeList(benchmarking.get("outlierPillars"));
        if (!outliers.isEmpty()) {
            s.labeledRow("Outlier pillars", ExcelWorkbookBuilder.bullets(outliers));
        }
        s.autoSize();
    }

    private List<String> resolveMemberNames(InsightReport report, int size, boolean showNames) {
        List<String> names = new ArrayList<>();
        if (showNames && report.getPipeline() != null && report.getOrganization() != null) {
            // Honour the memberIds filter captured at generation time so the
            // Excel never resolves a name that wasn't part of the AI input.
            // Sort by user.id to match the order InsightService used when
            // building the AI prompt — keeps "Member N" mapping stable across
            // the AI's coachingList and the PDF/Excel exports.
            Set<UUID> memberFilter = report.getMemberIds();
            List<Submission> submissions = submissionRepository.findByOrgAndPipeline(
                            report.getOrganization().getId(), report.getPipeline().getId())
                    .stream()
                    .filter(s -> s.getStatus() == SubmissionStatus.EVALUATED)
                    .filter(s -> memberFilter == null || memberFilter.isEmpty()
                            || memberFilter.contains(s.getUser().getId()))
                    .sorted(java.util.Comparator.comparing(s -> s.getUser().getId()))
                    .toList();
            for (int i = 0; i < size; i++) {
                if (i < submissions.size()) {
                    names.add(submissions.get(i).getUser().getName());
                } else {
                    names.add("Member " + (i + 1));
                }
            }
        } else {
            for (int i = 0; i < size; i++) names.add("Member " + (i + 1));
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeCast(Object obj) {
        return obj instanceof Map ? (Map<String, Object>) obj : null;
    }

    @SuppressWarnings("unchecked")
    private List<Object> safeList(Object obj) {
        return obj instanceof List ? (List<Object>) obj : List.of();
    }

    // ponytail: reports generated before the commonWeaknesses -> growthEdges
    // rename still have the old key; drop this fallback once old reports age out.
    private List<Object> growthEdges(Map<String, Object> teamThemes) {
        List<Object> edges = safeList(teamThemes.get("growthEdges"));
        return edges.isEmpty() ? safeList(teamThemes.get("commonWeaknesses")) : edges;
    }
}
