package com.bvisionry.insights.service;

import com.bvisionry.aiconfig.service.OpenRouterChatService;
import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.common.enums.InsightReportStatus;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.tx.AfterCommit;
import com.bvisionry.evaluation.PillarEvaluationRepository;
import com.bvisionry.evaluation.entity.PillarEvaluation;
import com.bvisionry.insights.InsightReportRepository;
import com.bvisionry.insights.dto.InsightGenerateResponse;
import com.bvisionry.insights.dto.InsightListResponse;
import com.bvisionry.insights.dto.InsightReportResponse;
import com.bvisionry.insights.entity.InsightReport;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.pipeline.repository.PipelineRepository;
import com.bvisionry.pipeline.entity.Pipeline;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InsightService {

    private final InsightReportRepository insightReportRepository;
    private final SubmissionRepository submissionRepository;
    private final PillarEvaluationRepository pillarEvaluationRepository;
    private final OrganizationRepository organizationRepository;
    private final PipelineRepository pipelineRepository;
    private final OpenRouterChatService openRouterChatService;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Lazy self-injection so @Async on runInsightGenerationAsync goes through the Spring proxy.
    @Autowired @Lazy
    private InsightService self;

    /**
     * Backwards-compatible overload — generates an org insight across every
     * evaluated member in the pipeline. Equivalent to passing a null/empty
     * member-id filter.
     */
    @Transactional
    public InsightGenerateResponse generateInsight(UUID orgId, UUID pipelineId) {
        return generateInsight(orgId, pipelineId, null);
    }

    /**
     * Kick off AI team-insight generation. Persists a stub row, commits, then
     * dispatches the AI call to a background thread so a Tomcat/proxy timeout
     * on the originating HTTP connection cannot drop the result.
     *
     * <p>{@code memberIds}, when non-empty, restricts the AI input to those
     * users' submissions. The filter is also stored on the report so the PDF /
     * Excel exports can re-resolve names against the same subset.
     */
    @Transactional
    public InsightGenerateResponse generateInsight(UUID orgId, UUID pipelineId, List<UUID> memberIds) {
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgId.toString()));
        Pipeline pipeline = pipelineRepository.findById(pipelineId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", pipelineId.toString()));

        Set<UUID> memberFilter = memberIds == null ? Set.of() : new HashSet<>(memberIds);

        // Aggregate up front so data-shape errors surface synchronously (BadRequest)
        // rather than disappearing into a FAILED row.
        String anonymizedData = aggregateAnonymizedData(orgId, pipelineId, memberFilter);

        InsightReport report = new InsightReport();
        report.setOrganization(org);
        report.setPipeline(pipeline);
        if (!memberFilter.isEmpty()) {
            report.setMemberIds(new HashSet<>(memberFilter));
        }
        InsightReport saved = insightReportRepository.save(report);
        UUID reportId = saved.getId();

        AfterCommit.run(() -> self.runInsightGenerationAsync(reportId, pipelineId, anonymizedData));

        return new InsightGenerateResponse(reportId, InsightReportStatus.GENERATING);
    }

    @Async("evaluationExecutor")
    public void runInsightGenerationAsync(UUID reportId, UUID pipelineId, String anonymizedData) {
        log.debug("runInsightGenerationAsync started for report {} on thread {}",
                reportId, Thread.currentThread().getName());
        try {
            var aiResponse = openRouterChatService.generateTeamInsight(
                    anonymizedData,
                    new com.bvisionry.aicalllog.dto.CallMetadata(null, pipelineId, null));

            if (aiResponse.isParsed()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> reportJson = objectMapper.convertValue(aiResponse.parsed(), Map.class);
                String model = aiResponse.provenance() != null
                        ? aiResponse.provenance().model() : "configured-model";
                completeInsight(reportId, reportJson, model);
            } else {
                // Fail loud: the model output failed schema validation even after the
                // engine's repair retries. Mark FAILED (retryable) instead of storing a
                // COMPLETED report with an unusable {rawResponse} blob that the admin UI
                // and PDF/Excel exports can't render.
                failInsight(reportId, "Team insight output failed schema validation after repair retries.");
            }
        } catch (Exception e) {
            log.error("Insight generation failed for report {}: {}", reportId, e.getMessage(), e);
            failInsight(reportId, e.getMessage());
        }
    }

    @Transactional
    public void completeInsight(UUID reportId, Map<String, Object> reportJson, String model) {
        InsightReport report = insightReportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalStateException("Insight report vanished mid-flight: " + reportId));
        report.setReportJson(reportJson);
        report.setAiModelUsed(model);
        report.setGeneratedAt(Instant.now());
        report.setStatus(InsightReportStatus.COMPLETED);
        report.setFailureReason(null);
        insightReportRepository.save(report);
        log.info("Insight {} marked COMPLETED", reportId);
    }

    @Transactional
    public void failInsight(UUID reportId, String reason) {
        insightReportRepository.findById(reportId).ifPresent(report -> {
            report.setStatus(InsightReportStatus.FAILED);
            report.setFailureReason(reason);
            insightReportRepository.save(report);
            log.info("Insight {} marked FAILED", reportId);
        });
    }

    /**
     * Re-runs a previously-FAILED team-insight report in place — the analogue of
     * {@code EvaluationService.retryFailedSubmission}. Re-aggregates from the same
     * pipeline + member filter, flips the row back to GENERATING, and dispatches a
     * fresh async generation. Without this, a single transient provider error or a
     * one-off schema-validation failure permanently dead-ended the report.
     */
    @Transactional
    public InsightGenerateResponse retryFailedInsight(UUID orgId, UUID reportId) {
        InsightReport report = insightReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("InsightReport", reportId.toString()));
        if (!report.getOrganization().getId().equals(orgId)) {
            throw new ResourceNotFoundException("InsightReport", reportId.toString());
        }
        if (report.getStatus() != InsightReportStatus.FAILED) {
            throw new BadRequestException(
                    "Only FAILED insight reports can be retried (was " + report.getStatus() + ")");
        }

        UUID pipelineId = report.getPipeline().getId();
        Set<UUID> memberFilter = report.getMemberIds() == null ? Set.of() : new HashSet<>(report.getMemberIds());
        // Re-aggregate synchronously so a now-empty selection surfaces as BadRequest
        // instead of dead-ending in another FAILED row.
        String anonymizedData = aggregateAnonymizedData(orgId, pipelineId, memberFilter);

        report.setStatus(InsightReportStatus.GENERATING);
        report.setFailureReason(null);
        insightReportRepository.save(report);

        AfterCommit.run(() -> self.runInsightGenerationAsync(reportId, pipelineId, anonymizedData));
        return new InsightGenerateResponse(reportId, InsightReportStatus.GENERATING);
    }

    @Transactional(readOnly = true)
    public InsightReportResponse getReport(UUID orgId, UUID reportId) {
        InsightReport report = insightReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("InsightReport", reportId.toString()));

        if (!report.getOrganization().getId().equals(orgId)) {
            throw new ResourceNotFoundException("InsightReport", reportId.toString());
        }

        return toResponse(report);
    }

    /** Excludes in-flight stubs so the admin list never shows placeholders for runs that haven't finished. */
    @Transactional(readOnly = true)
    public InsightListResponse listReports(UUID orgId) {
        List<InsightReport> reports = insightReportRepository
                .findByOrganizationIdAndStatusOrderByGeneratedAtDesc(orgId, InsightReportStatus.COMPLETED);

        List<InsightListResponse.InsightSummary> summaries = reports.stream()
                .map(report -> new InsightListResponse.InsightSummary(
                        report.getId(),
                        report.getPipeline().getId(),
                        report.getPipeline().getName(),
                        report.getAiModelUsed(),
                        report.getGeneratedAt()
                ))
                .toList();

        return new InsightListResponse(summaries);
    }

    /** Returns the most recent row regardless of status so the frontend can poll GENERATING / surface FAILED. */
    @Transactional(readOnly = true)
    public java.util.Optional<InsightReportResponse> getLatestOrgInsight(UUID orgId, UUID pipelineId) {
        return insightReportRepository.findTopByOrganizationIdAndPipelineIdOrderByGeneratedAtDesc(orgId, pipelineId)
                .map(this::toResponse);
    }

    private InsightReportResponse toResponse(InsightReport report) {
        return new InsightReportResponse(
                report.getId(),
                report.getPipeline().getId(),
                report.getPipeline().getName(),
                report.getReportJson(),
                report.getAiModelUsed(),
                report.getGeneratedAt(),
                report.getStatus(),
                report.getFailureReason()
        );
    }

    /**
     * Resolve the EVALUATED submissions for this org+pipeline (optionally filtered
     * to a member subset), limit pillar evaluations to those submissions, and build
     * the anonymized prompt input. Sorted by user.id so the "Member N" numbering is
     * stable across runs and matches the position-based masking the PDF/Excel
     * exports use. Throws {@link BadRequestException} if nothing matches.
     */
    private String aggregateAnonymizedData(UUID orgId, UUID pipelineId, Set<UUID> memberFilter) {
        List<Submission> submissions = submissionRepository.findByOrgAndPipeline(orgId, pipelineId)
                .stream()
                .filter(s -> s.getStatus() == SubmissionStatus.EVALUATED)
                .filter(s -> memberFilter.isEmpty() || memberFilter.contains(s.getUser().getId()))
                .sorted(java.util.Comparator.comparing(s -> s.getUser().getId()))
                .toList();
        if (submissions.isEmpty()) {
            throw new BadRequestException(memberFilter.isEmpty()
                    ? "No evaluated submissions found for this pipeline"
                    : "None of the selected members have evaluated submissions for this pipeline");
        }

        Set<UUID> submissionIds = submissions.stream()
                .map(Submission::getId)
                .collect(Collectors.toSet());
        List<PillarEvaluation> evaluations = pillarEvaluationRepository
                .findByOrgAndPipeline(orgId, pipelineId)
                .stream()
                .filter(e -> submissionIds.contains(e.getSubmission().getId()))
                .toList();
        return buildAnonymizedData(submissions, evaluations);
    }

    /**
     * Build anonymized aggregate data for the AI prompt.
     * Uses member IDs (not names or emails) to maintain privacy.
     */
    private String buildAnonymizedData(List<Submission> submissions, List<PillarEvaluation> evaluations) {
        Map<UUID, List<PillarEvaluation>> byPillar = evaluations.stream()
                .collect(Collectors.groupingBy(e -> e.getPillar().getId()));

        StringBuilder sb = new StringBuilder();
        sb.append("Team size: ").append(submissions.size()).append(" members evaluated.\n\n");

        for (Map.Entry<UUID, List<PillarEvaluation>> entry : byPillar.entrySet()) {
            List<PillarEvaluation> pillarEvals = entry.getValue();
            String pillarName = pillarEvals.getFirst().getPillar().getName();

            BigDecimal avgScore = pillarEvals.stream()
                    .map(PillarEvaluation::getScorePercentage)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(pillarEvals.size()), 2, RoundingMode.HALF_UP);

            Map<String, Long> maturityCounts = pillarEvals.stream()
                    .collect(Collectors.groupingBy(PillarEvaluation::getMaturityLabel, Collectors.counting()));

            sb.append("Pillar: ").append(pillarName).append("\n");
            sb.append("  Average score: ").append(avgScore).append("%\n");
            sb.append("  Maturity distribution: ").append(maturityCounts).append("\n");

            List<String> allStrengths = pillarEvals.stream()
                    .filter(e -> e.getAiWhatsWorking() != null)
                    .flatMap(e -> e.getAiWhatsWorking().stream())
                    .toList();
            List<String> allImprovements = pillarEvals.stream()
                    .filter(e -> e.getAiWhatCanImprove() != null)
                    .flatMap(e -> e.getAiWhatCanImprove().stream())
                    .toList();

            sb.append("  Common strengths mentioned: ").append(allStrengths).append("\n");
            sb.append("  Common improvement areas: ").append(allImprovements).append("\n\n");
        }

        // Per-member scores in submission order — submissions arrived sorted by
        // user.id, so iterating that list (rather than the HashMap groupingBy)
        // keeps the AI input deterministic across runs.
        Map<UUID, List<PillarEvaluation>> bySubmission = evaluations.stream()
                .collect(Collectors.groupingBy(e -> e.getSubmission().getId()));

        sb.append("Individual member scores:\n");
        // PII guard: never emit the real name/email into the aggregate, since it is
        // sent verbatim to the external LLM and persisted in ai_call_logs. Use a
        // positional "Member N" label instead. Submissions are pre-sorted by user.id
        // (see generateInsight), so this numbering is stable across runs and matches
        // the position-based masking the PDF/Excel exports use to re-resolve names.
        int memberIndex = 0;
        for (Submission submission : submissions) {
            List<PillarEvaluation> memberEvals = bySubmission.get(submission.getId());
            if (memberEvals == null || memberEvals.isEmpty()) continue;
            memberIndex++;
            sb.append("  Member ").append(memberIndex).append(": ");
            for (PillarEvaluation eval : memberEvals) {
                sb.append(eval.getPillar().getName()).append("=")
                        .append(eval.getScorePercentage()).append("% ");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
