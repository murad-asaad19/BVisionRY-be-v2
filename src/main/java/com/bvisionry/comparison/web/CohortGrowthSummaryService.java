package com.bvisionry.comparison.web;

import com.bvisionry.aicalllog.dto.CallMetadata;
import com.bvisionry.aiconfig.service.OpenRouterChatService;
import com.bvisionry.common.audit.AuditLogger;
import com.bvisionry.common.enums.InsightReportStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.programaccess.OrgCohortAccess;
import com.bvisionry.common.tx.AfterCommit;
import com.bvisionry.comparison.domain.CohortGrowthSummary;
import com.bvisionry.comparison.domain.FounderComparison;
import com.bvisionry.comparison.domain.FounderComparisonPillar;
import com.bvisionry.comparison.domain.NarrativeStatus;
import com.bvisionry.comparison.domain.ShiftNarrative;
import com.bvisionry.comparison.dto.CohortGrowthSummaryDto;
import com.bvisionry.comparison.repository.CohortGrowthSummaryRepository;
import com.bvisionry.comparison.repository.ComparisonReadRepository;
import com.bvisionry.comparison.repository.ComparisonReadRepository.RawPillarText;
import com.bvisionry.comparison.repository.FounderComparisonPillarRepository;
import com.bvisionry.comparison.repository.FounderComparisonRepository;
import com.bvisionry.comparison.repository.ShiftNarrativeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The cohort-level growth report (redesign spec §4) — org insights' lifecycle,
 * cohort-scoped.
 *
 * <p>Deliberately built as a copy of {@code InsightService}'s shape rather than
 * as another review-gated narrative: aggregate SYNCHRONOUSLY (so a data-shape
 * problem is a 400 the clicker sees, not a FAILED row they have to go find),
 * persist a GENERATING stub, dispatch the model call after commit so a proxy
 * timeout cannot drop the result, and keep FAILED rows retryable. §4 gives it
 * no review gate at all: staff-only, auto-published.
 *
 * <p><b>Partial coverage is the normal case.</b> The report generates from
 * whatever narratives are approved, and the row stores how many members that
 * was, so the UI can say "narratives included for 8/12 members" rather than
 * implying it saw everyone.
 *
 * <p><b>Names.</b> "Member N" by user-id order, exactly the numbering
 * {@code InsightService.buildAnonymizedData} and the insight exports use, unless
 * the generation asks for real names — which ANY org admin may do (§4, operator
 * decision), so there is no {@code ExportNameGuard} here on purpose. The choice
 * is stamped on the row.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CohortGrowthSummaryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CohortGrowthSummaryRepository summaries;
    private final FounderComparisonRepository comparisons;
    private final FounderComparisonPillarRepository comparisonPillars;
    private final ShiftNarrativeRepository narrativeRows;
    private final ComparisonReadRepository reads;
    private final OrgCohortAccess orgCohorts;
    private final OpenRouterChatService chat;
    private final AuditLogger auditLogger;

    /**
     * Lazy self-lookup so {@code @Async} on {@link #runAsync} goes through the
     * Spring proxy. An {@code ObjectProvider} rather than {@code @Autowired @Lazy}
     * on a field: same deferred resolution, no field injection for the ArchUnit
     * rule to (rightly) object to.
     */
    private final ObjectProvider<CohortGrowthSummaryService> self;

    /* ======================================================== generation */

    @Transactional
    public CohortGrowthSummaryDto generate(UUID orgId, UUID cohortId, boolean includeNames, UUID actorId) {
        requireCohortInOrg(orgId, cohortId);
        Aggregate aggregate = aggregate(orgId, cohortId, includeNames);

        CohortGrowthSummary row = new CohortGrowthSummary();
        row.setCohortId(cohortId);
        row.setOrgId(orgId);
        row.setIncludeNames(includeNames);
        stamp(row, aggregate);
        CohortGrowthSummary saved = summaries.save(row);
        UUID summaryId = saved.getId();

        auditLogger.log(actorId, orgId, "COHORT_GROWTH_SUMMARY_GENERATED",
                "CohortGrowthSummary", summaryId,
                Map.of("cohortId", cohortId, "includeNames", includeNames,
                        "membersTotal", aggregate.membersTotal(),
                        "membersWithNarratives", aggregate.membersWithNarratives()));

        AfterCommit.dispatch(() -> self.getObject().runAsync(orgId, summaryId, aggregate.input()));
        return toDto(saved);
    }

    /**
     * Re-runs a FAILED report in place. Re-aggregates first, and synchronously,
     * so a cohort whose comparisons have since been wiped answers 400 instead of
     * dead-ending in a second FAILED row — the same reasoning as
     * {@code InsightService.retryFailedInsight}.
     */
    @Transactional
    public CohortGrowthSummaryDto retry(UUID orgId, UUID summaryId, UUID actorId) {
        CohortGrowthSummary row = summaries.findByIdAndOrgId(summaryId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort growth summary", summaryId.toString()));
        if (row.getStatus() != InsightReportStatus.FAILED) {
            throw new BadRequestException(
                    "Only FAILED cohort growth summaries can be retried (was " + row.getStatus() + ")");
        }

        Aggregate aggregate = aggregate(orgId, row.getCohortId(), row.isIncludeNames());
        row.setStatus(InsightReportStatus.GENERATING);
        row.setFailureReason(null);
        stamp(row, aggregate);
        summaries.save(row);

        auditLogger.log(actorId, orgId, "COHORT_GROWTH_SUMMARY_RETRIED",
                "CohortGrowthSummary", summaryId, Map.of("cohortId", row.getCohortId()));

        AfterCommit.dispatch(() -> self.getObject().runAsync(orgId, summaryId, aggregate.input()));
        return toDto(row);
    }

    @Async("evaluationExecutor")
    public void runAsync(UUID orgId, UUID summaryId, String input) {
        run(orgId, summaryId, input);
    }

    /** Synchronous entry point — the async wrapper above, and the tests, call this. */
    void run(UUID orgId, UUID summaryId, String input) {
        try {
            var response = chat.generateCohortGrowthSummary(input, new CallMetadata(null, null, null));
            if (!response.isParsed()) {
                // Fail loud rather than storing a COMPLETED row holding an
                // unrenderable blob: the model's output failed schema validation
                // even after the engine's repair retries.
                fail(orgId, summaryId,
                        "Cohort growth summary output failed schema validation after repair retries.");
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> reportJson = MAPPER.convertValue(response.parsed(), Map.class);
            complete(orgId, summaryId, reportJson, response.provenance() != null
                    ? response.provenance().model() : "configured-model");
        } catch (Exception e) {
            log.error("Cohort growth summary {} failed: {}", summaryId, e.getMessage(), e);
            fail(orgId, summaryId, e.getMessage());
        }
    }

    @Transactional
    public void complete(UUID orgId, UUID summaryId, Map<String, Object> reportJson, String model) {
        CohortGrowthSummary row = summaries.findByIdAndOrgId(summaryId, orgId)
                .orElseThrow(() -> new IllegalStateException(
                        "Cohort growth summary vanished mid-flight: " + summaryId));
        row.setReportJson(reportJson);
        row.setAiModelUsed(model);
        row.setGeneratedAt(Instant.now());
        row.setStatus(InsightReportStatus.COMPLETED);
        row.setFailureReason(null);
        summaries.save(row);
        log.info("Cohort growth summary {} marked COMPLETED", summaryId);
    }

    @Transactional
    public void fail(UUID orgId, UUID summaryId, String reason) {
        summaries.findByIdAndOrgId(summaryId, orgId).ifPresent(row -> {
            row.setStatus(InsightReportStatus.FAILED);
            row.setFailureReason(reason);
            summaries.save(row);
            log.info("Cohort growth summary {} marked FAILED", summaryId);
        });
    }

    /* ====================================================== the read side */

    /** The poll target: newest row whatever its status, so GENERATING and FAILED both surface. */
    @Transactional(readOnly = true)
    public Optional<CohortGrowthSummaryDto> latest(UUID orgId, UUID cohortId) {
        requireCohortInOrg(orgId, cohortId);
        return summaries.findTopByOrgIdAndCohortIdOrderByGeneratedAtDesc(orgId, cohortId)
                .map(CohortGrowthSummaryService::toDto);
    }

    @Transactional(readOnly = true)
    public List<CohortGrowthSummaryDto> history(UUID orgId, UUID cohortId) {
        requireCohortInOrg(orgId, cohortId);
        return summaries.findByOrgIdAndCohortIdOrderByGeneratedAtDesc(orgId, cohortId).stream()
                .map(CohortGrowthSummaryService::toDto)
                .toList();
    }

    /* ------------------------------------------------------- the aggregate */

    /** The prompt input plus the coverage counters stamped on the row beside it. */
    record Aggregate(String input, int membersTotal, int membersWithNarratives) {
    }

    /**
     * The model's ONLY input, built from the three sources §4 names. Synchronous
     * and fail-loud: a cohort with nothing computed gets a 400, never a FAILED
     * row nobody asked for.
     *
     * <p>Package-private so the integration test can assert what is PUT IN FRONT
     * of the model — anonymised or not — rather than only what came back.
     */
    Aggregate aggregate(UUID orgId, UUID cohortId, boolean includeNames) {
        // user.id order: the same stable "Member N" numbering the org insight and
        // its exports use, so the labels mean the same thing across surfaces.
        List<FounderComparison> rows = comparisons.findByOrgIdAndCohortId(orgId, cohortId).stream()
                .sorted(Comparator.comparing(FounderComparison::getUserId))
                .toList();
        if (rows.isEmpty()) {
            throw new BadRequestException(
                    "This cohort has no computed comparisons yet; there is nothing to summarise");
        }

        Map<UUID, String> labels = labels(rows, includeNames);
        Map<UUID, List<ShiftNarrative>> narrativesByUser = narrativeRows
                .findByOrgIdAndCohortIdAndStatus(orgId, cohortId, NarrativeStatus.APPROVED).stream()
                .collect(Collectors.groupingBy(ShiftNarrative::getUserId));

        StringBuilder sb = new StringBuilder("COHORT: ")
                .append(rows.size()).append(" members with a computed comparison.\n\n");

        // 1. per-member before/after rows.
        // ponytail: one pillar-row read + one text-block read per member. A cohort
        // is a classroom, not a dataset; a single joined query is the upgrade path
        // if a cohort ever grows past a few hundred.
        Map<String, PillarAggregate> pillarAggregates = new TreeMap<>();
        sb.append("PER-MEMBER COMPARISON:\n");
        for (FounderComparison c : rows) {
            String label = labels.get(c.getUserId());
            sb.append(label).append(" — overall ").append(pct(c.getOverallBefore()))
                    .append(" → ").append(pct(c.getOverallAfter()))
                    .append(" (").append(delta(c.getOverallDelta()))
                    .append(band(c.getOverallBandLabel())).append(")\n");

            Map<UUID, RawPillarText> afterText = reads.pillarTextBlocks(c.getDistanceSubmissionId());
            for (FounderComparisonPillar p : comparisonPillars.findByComparisonId(c.getId())) {
                sb.append("  ").append(p.getPillarNameSnapshot()).append(": ")
                        .append(pct(p.getBeforePct())).append(" → ").append(pct(p.getAfterPct()))
                        .append(" (").append(delta(p.getDelta())).append(band(p.getBandLabel()))
                        .append(")\n");
                accumulate(pillarAggregates, p, afterText.get(p.getDistancePillarId()));
            }
        }

        // 2. the human-approved narratives, per member, per pillar.
        sb.append("\nAPPROVED NARRATIVES:\n");
        if (narrativesByUser.isEmpty()) {
            sb.append("(none approved yet for this cohort)\n");
        }
        for (FounderComparison c : rows) {
            for (ShiftNarrative n : narrativesByUser.getOrDefault(c.getUserId(), List.of())) {
                sb.append(labels.get(c.getUserId())).append(" — ")
                        .append(n.getPillarNameSnapshot()).append('\n');
                if (n.getItems() == null || n.getItems().isEmpty()) {
                    // A pre-V189 row: its single paragraph IS the whole breakdown.
                    sb.append("- ").append(n.getKind()).append(": ").append(n.getBody()).append('\n');
                } else {
                    n.getItems().forEach(item -> sb.append("- ").append(item.kind().name())
                            .append(": ").append(item.text()).append('\n'));
                }
            }
        }

        // 3. the org-insights-style pillar aggregate, scoped to this cohort's
        //    distance submissions.
        sb.append("\nPILLAR AGGREGATE:\n");
        pillarAggregates.forEach((name, agg) -> sb.append(agg.render(name)));

        long withNarratives = rows.stream()
                .filter(c -> narrativesByUser.containsKey(c.getUserId()))
                .count();
        return new Aggregate(sb.toString(), rows.size(), (int) withNarratives);
    }

    /** "Member N" by user-id order, or the real names when §4's toggle is on. */
    private Map<UUID, String> labels(List<FounderComparison> rows, boolean includeNames) {
        Map<UUID, String> realNames = includeNames
                ? reads.userNames(rows.stream().map(FounderComparison::getUserId).toList())
                : Map.of();
        Map<UUID, String> labels = new LinkedHashMap<>();
        int index = 0;
        for (FounderComparison c : rows) {
            index++;
            String real = realNames.get(c.getUserId());
            labels.put(c.getUserId(), real != null ? real : "Member " + index);
        }
        return labels;
    }

    /** One pillar's cohort-wide picture — the {@code buildAnonymizedData} block, per pillar. */
    private static final class PillarAggregate {
        private final List<BigDecimal> afterScores = new ArrayList<>();
        private final Map<String, Integer> maturityAfter = new TreeMap<>();
        private final List<String> strengths = new ArrayList<>();
        private final List<String> improvements = new ArrayList<>();

        String render(String pillarName) {
            StringBuilder sb = new StringBuilder("Pillar: ").append(pillarName).append('\n');
            if (!afterScores.isEmpty()) {
                BigDecimal avg = afterScores.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(afterScores.size()), 2, RoundingMode.HALF_UP);
                sb.append("  Average score after: ").append(avg).append("%\n");
            }
            sb.append("  Maturity distribution after: ").append(maturityAfter).append('\n');
            sb.append("  Common strengths mentioned: ").append(strengths).append('\n');
            sb.append("  Common improvement areas: ").append(improvements).append("\n\n");
            return sb.toString();
        }
    }

    private static void accumulate(Map<String, PillarAggregate> aggregates,
                                   FounderComparisonPillar pillar, RawPillarText afterText) {
        PillarAggregate agg = aggregates.computeIfAbsent(pillar.getPillarNameSnapshot(),
                name -> new PillarAggregate());
        if (pillar.getAfterPct() != null) {
            agg.afterScores.add(pillar.getAfterPct());
        }
        if (pillar.getMaturityAfter() != null) {
            agg.maturityAfter.merge(pillar.getMaturityAfter(), 1, Integer::sum);
        }
        agg.strengths.addAll(ShiftNarrativeService.lines(afterText, true));
        agg.improvements.addAll(ShiftNarrativeService.lines(afterText, false));
    }

    /* ---------------------------------------------------------- plumbing */

    private void requireCohortInOrg(UUID orgId, UUID cohortId) {
        orgCohorts.cohortNameInOrg(orgId, cohortId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));
    }

    private static void stamp(CohortGrowthSummary row, Aggregate aggregate) {
        row.setMembersTotal(aggregate.membersTotal());
        row.setMembersWithNarratives(aggregate.membersWithNarratives());
    }

    private static String pct(BigDecimal value) {
        return value == null ? "n/a" : value + "%";
    }

    private static String delta(BigDecimal value) {
        if (value == null) {
            return "no change recorded";
        }
        return (value.signum() >= 0 ? "+" : "") + value;
    }

    private static String band(String bandLabel) {
        return bandLabel == null ? "" : ", " + bandLabel;
    }

    private static CohortGrowthSummaryDto toDto(CohortGrowthSummary row) {
        return new CohortGrowthSummaryDto(row.getId(), row.getCohortId(), row.getStatus(),
                row.getReportJson(), row.getFailureReason(), row.isIncludeNames(),
                row.getMembersTotal(), row.getMembersWithNarratives(),
                row.getAiModelUsed(), row.getGeneratedAt());
    }
}
