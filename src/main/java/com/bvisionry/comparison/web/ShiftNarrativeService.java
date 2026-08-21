package com.bvisionry.comparison.web;

import com.bvisionry.aicalllog.dto.CallMetadata;
import com.bvisionry.aiconfig.service.OpenRouterChatService;
import com.bvisionry.aiconfig.service.OpenRouterChatService.AIResponse;
import com.bvisionry.common.audit.AuditLogger;
import com.bvisionry.common.dto.ShiftNarrativeResult;
import com.bvisionry.common.exception.AIServiceException;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.scoringconfig.NarrativeWording;
import com.bvisionry.comparison.domain.FounderComparison;
import com.bvisionry.comparison.domain.FounderComparisonPillar;
import com.bvisionry.comparison.domain.NarrativeKind;
import com.bvisionry.comparison.domain.NarrativeStatus;
import com.bvisionry.comparison.domain.PillarComparisonState;
import com.bvisionry.comparison.domain.ShiftNarrative;
import com.bvisionry.comparison.dto.GenerateAllNarrativesResponse;
import com.bvisionry.comparison.dto.NarrativeReviewResponse;
import com.bvisionry.comparison.dto.NarrativeReviewResponse.NarrativePillarOption;
import com.bvisionry.comparison.dto.NarrativeRequests.UpdateNarrativeRequest;
import com.bvisionry.comparison.dto.ShiftNarrativeDto;
import com.bvisionry.comparison.repository.ComparisonReadRepository;
import com.bvisionry.comparison.repository.ComparisonReadRepository.RawPillarText;
import com.bvisionry.comparison.repository.NarrativeActivityRepository;
import com.bvisionry.comparison.repository.FounderComparisonPillarRepository;
import com.bvisionry.comparison.repository.FounderComparisonRepository;
import com.bvisionry.comparison.repository.ShiftNarrativeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The Qualitative Shift Narrative job + review gate (redesign spec §6, output
 * upgraded to the §2 breakdown).
 *
 * <h2>What the model sees</h2>
 * The pillar name, the direction word, the before/after percentages (context
 * for gauging the size of the shift — operator decision 2026-08-19), the two
 * text blocks ("what's working" / "what can improve") from the baseline and
 * distance evaluations, and the programme work tagged to that pillar
 * ({@link NarrativeActivityRepository}). "No generation from scores alone" still
 * holds: the length floors below refuse a pillar whose text is missing, so a
 * score with nothing to ground it never generates.
 *
 * <p>The output MAY state the figures it was given — the no-numbers rule was
 * retired (operator decision 2026-08-20, V205); what the prompt still forbids
 * is stating a figure the material does not contain. The prompt also carries
 * the one rule the data cannot: never quote the facilitator feedback the
 * activity section labels as such.
 *
 * <h2>Guardrails (code, not prompt-hope)</h2>
 * See {@link NarrativeGuardrails}. Either length floor — no "before" to compare
 * from, or nothing at all to compare it against — means no model call and NO row
 * (the review UI shows the configured standard sentence inline for that pillar;
 * a row would imply a narrative somebody could approve). Decline without a
 * forward-looking close, an empty breakdown, or a kind outside
 * {@link NarrativeKind} → ONE corrective retry, then a generation failure that persists
 * nothing.
 *
 * <h2>Review gate</h2>
 * DRAFT → APPROVED, and only APPROVED reaches the member's My Growth / PDF /
 * Excel. Editing an APPROVED narrative returns it to DRAFT — an edit is new
 * prose the founder has never had signed off, and silently keeping the approval
 * stamp on it would make the stamp meaningless. The platform auto-approve
 * toggle (§6, off by default) lands generated narratives APPROVED with a system
 * stamp (no {@code approvedBy}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShiftNarrativeService {

    /**
     * The model gets one draft and exactly one corrective re-ask (§6). A third
     * try would just re-bill the same misunderstanding: when a model cannot
     * classify into a known kind or close a decline after being told to, the
     * honest outcome is a generation failure a human can see.
     */
    static final int MAX_ATTEMPTS = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final ShiftNarrativeRepository narratives;
    private final com.bvisionry.comparison.repository.MemberGrowthSummaryRepository growthSummaries;
    private final FounderComparisonRepository comparisons;
    private final FounderComparisonPillarRepository comparisonPillars;
    private final ComparisonReadRepository reads;
    private final NarrativeActivityRepository activityReads;
    private final OpenRouterChatService chat;
    private final AuditLogger auditLogger;

    /* ======================================================== generation */

    /**
     * Fire-and-forget generation for every eligible shift pillar of a freshly
     * computed comparison. Runs on the evaluation executor so it never blocks
     * the compute transaction, and swallows everything: a narrative failure must
     * never fail — or un-compute — the comparison (§6).
     */
    @Async("evaluationExecutor")
    public void generateAllPillarsAsync(UUID cohortId, UUID userId) {
        try {
            generateAllPillars(cohortId, userId);
        } catch (RuntimeException e) {
            log.warn("Shift-narrative generation failed for cohort {} user {} "
                    + "(comparison unaffected): {}", cohortId, userId, e.getMessage(), e);
        }
    }

    /** Synchronous entry point — the async wrapper above, and the tests, call this. */
    public int generateAllPillars(UUID cohortId, UUID userId) {
        GenerateAllNarrativesResponse result = generateBatch(context(cohortId, userId), false);
        log.info("Generated {} shift narratives ({} skipped, {} failed) for cohort {} user {}",
                result.generated(), result.skipped(), result.failed(), cohortId, userId);
        return result.generated();
    }

    /**
     * The staff "Generate narratives" button: every remaining eligible pillar of
     * the founder's anchor comparison, in one synchronous call. Same anchor and
     * same candidate rules as the review payload, so "N pillars remaining" on
     * the button and what this actually generates can never disagree.
     *
     * <p>Like {@link #generateForPillar}, deliberately NOT {@code @Transactional}:
     * this loops one model call per pillar, and a database connection must not
     * sit open across that many provider round-trips.
     */
    public GenerateAllNarrativesResponse generateAllForFounder(UUID userId, UUID actorId) {
        return generateAllForFounder(userId, actorId, false);
    }

    /**
     * {@code regenerate = true} is the "the underlying data changed, redo the
     * set" button: existing narratives — approved ones included — are replaced
     * in place (model call first, overwrite on success), everything missing is
     * generated as usual. Because the replacement discards prose the growth
     * summary may have been written from, an APPROVED summary is returned to
     * draft, exactly as a recompute would.
     */
    public GenerateAllNarrativesResponse generateAllForFounder(UUID userId, UUID actorId,
                                                               boolean regenerate) {
        Context ctx = context(anchorCohort(userId), userId);
        GenerateAllNarrativesResponse result = generateBatch(ctx, regenerate);
        if (regenerate && result.generated() > 0) {
            revertSummaryFor(ctx);
        }
        auditLogger.log(actorId, ctx.comparison().getOrgId(),
                regenerate ? "SHIFT_NARRATIVES_REGENERATED_ALL" : "SHIFT_NARRATIVES_GENERATED_ALL",
                "FounderComparison", ctx.comparison().getId(),
                Map.of("generated", result.generated(), "skipped", result.skipped(),
                        "failed", result.failed()));
        return result;
    }

    /**
     * The one batch loop both the post-compute job and the staff button run.
     * Biggest absolute shift first, so the biggest stories land even if a later
     * call fails; pillars that already have a narrative are not candidates at
     * all. A concurrent duplicate — two staff clicking at once, or the async
     * batch racing a click — dies on the V169 unique constraint at save time;
     * that is a SKIP (somebody's draft is there), never a failure.
     */
    private GenerateAllNarrativesResponse generateBatch(Context ctx, boolean regenerate) {
        List<Candidate> candidates = ctx.candidates().stream()
                .filter(Candidate::hasEnoughMaterial)
                .sorted(Comparator.comparingDouble(
                        (Candidate c) -> Math.abs(c.pillar().getDelta().doubleValue())).reversed())
                .filter(c -> regenerate || !ctx.existingPillarIds().contains(c.distancePillarId()))
                .toList();

        List<GenerateAllNarrativesResponse.NarrativePillarOutcome> outcomes = new ArrayList<>();
        int generated = 0;
        int skipped = 0;
        int failed = 0;
        for (Candidate candidate : candidates) {
            String outcome;
            String reason = null;
            try {
                Generation generation = generateOne(ctx, candidate,
                        regenerate ? ctx.existingByPillar().get(candidate.distancePillarId()) : null);
                if (generation.saved() != null) {
                    outcome = "GENERATED";
                    generated++;
                } else {
                    outcome = "FAILED";
                    reason = NarrativeGuardrails.reasonLabel(generation.rejection());
                    failed++;
                }
            } catch (DataIntegrityViolationException e) {
                log.info("Shift narrative for pillar '{}' was generated concurrently — skipping",
                        candidate.pillar().getPillarNameSnapshot());
                outcome = "SKIPPED";
                skipped++;
            } catch (AIServiceException e) {
                // A provider outage on ONE pillar must not abort the rest of
                // the batch — the pillar stays generatable and says why.
                log.warn("Shift narrative for pillar '{}' hit a provider error: {}",
                        candidate.pillar().getPillarNameSnapshot(), e.getMessage());
                outcome = "FAILED";
                reason = "The AI provider could not be reached.";
                failed++;
            }
            outcomes.add(new GenerateAllNarrativesResponse.NarrativePillarOutcome(
                    candidate.distancePillarId(), candidate.pillar().getPillarNameSnapshot(),
                    outcome, reason));
        }
        return new GenerateAllNarrativesResponse(generated, skipped, failed, outcomes);
    }

    /**
     * On-demand generation for one mapped pillar ("Generate narrative for
     * another pillar…"). Unlike the batch path this one is a foreground request,
     * so its refusals are 400s the reviewer can read.
     *
     * <p>Deliberately NOT {@code @Transactional}: it makes a model call, and
     * holding a database connection open across a provider round-trip is how a
     * pool gets exhausted by a handful of reviewers clicking at once.
     */
    public ShiftNarrativeDto generateForPillar(UUID userId, UUID distancePillarId, UUID actorId) {
        return generateForPillar(userId, distancePillarId, actorId, false);
    }

    /**
     * {@code regenerate = true} replaces the pillar's existing narrative in
     * place — model call first, overwrite only on success, so a failed
     * generation leaves the old prose (approved or not) untouched. An APPROVED
     * member growth summary is returned to draft when the replacement lands: it
     * was written from prose that no longer exists.
     */
    public ShiftNarrativeDto generateForPillar(UUID userId, UUID distancePillarId, UUID actorId,
                                               boolean regenerate) {
        Context ctx = context(anchorCohort(userId), userId);
        ShiftNarrative existing = ctx.existingByPillar().get(distancePillarId);
        if (existing != null && !regenerate) {
            throw new BadRequestException("This pillar already has a narrative.");
        }
        Candidate candidate = ctx.candidates().stream()
                .filter(c -> distancePillarId.equals(c.distancePillarId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Mapped comparison pillar", distancePillarId.toString()));
        if (!candidate.hasEnoughMaterial()) {
            throw new BadRequestException(ctx.wording().notEnoughDataSentence());
        }
        ShiftNarrative saved;
        try {
            Generation generation = generateOne(ctx, candidate, existing);
            if (generation.saved() == null) {
                throw new BadRequestException(
                        NarrativeGuardrails.reasonLabel(generation.rejection())
                                + " Nothing was saved — try again in a moment.");
            }
            saved = generation.saved();
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent generation (V169 unique constraint):
            // the pillar HAS a narrative now, which is the same refusal as above,
            // not a 500.
            throw new BadRequestException("This pillar already has a narrative.");
        }
        if (existing != null) {
            revertSummaryFor(ctx);
        }
        auditLogger.log(actorId, ctx.comparison().getOrgId(),
                existing != null ? "SHIFT_NARRATIVE_REGENERATED" : "SHIFT_NARRATIVE_GENERATED",
                "ShiftNarrative", saved.getId(), Map.of("pillar", saved.getPillarNameSnapshot()));
        return toDto(saved, names(List.of(saved)), false);
    }

    /** One pillar's generation verdict: exactly one of the two is non-null. */
    private record Generation(ShiftNarrative saved, NarrativeGuardrails.Rejection rejection) {
    }

    /**
     * Regeneration's summary rule: replaced pillar prose invalidates an APPROVED
     * growth summary the same way a recompute does — it was synthesised from
     * narratives that no longer exist, so the stamp has to be re-earned. Straight
     * to the repository: {@code MemberGrowthSummaryService} depends on this class,
     * so the service route would be a constructor cycle.
     */
    private void revertSummaryFor(Context ctx) {
        int reverted = growthSummaries.revertApprovalsForCohortMembers(
                ctx.comparison().getCohortId(), List.of(ctx.comparison().getUserId()));
        if (reverted > 0) {
            log.info("Regeneration returned the approved growth summary to draft for cohort {} user {}",
                    ctx.comparison().getCohortId(), ctx.comparison().getUserId());
        }
    }

    /**
     * One model call plus, at most, one corrective retry. Returns the rejection
     * (with NOTHING persisted) when the output still fails the guardrails —
     * deliberately, rather than saving a decline with no way forward or an
     * unclassifiable kind.
     */
    private Generation generateOne(Context ctx, Candidate candidate, ShiftNarrative existing) {
        String userMessage = userMessage(candidate);
        CallMetadata metadata = new CallMetadata(ctx.comparison().getDistanceSubmissionId(), null,
                candidate.pillar().getPillarNameSnapshot());
        String bandKey = candidate.pillar().getBandKey();
        // Structural, not configural: the delta's sign cannot be renamed away.
        boolean decline = candidate.pillar().getDelta().signum() < 0;

        // One call, then at most ONE corrective re-ask (§6). Written as a bounded
        // loop rather than a copy-pasted second call so the retry budget is a
        // number, not a duplicated block — and so the aiconfig call site is one
        // place, not two (the architecture ratchet counts every one of them).
        // Coverage shares the same single-retry budget: a hard-rule rejection
        // claims the re-ask first, a coverage gap claims it otherwise — and a
        // gap that SURVIVES the retry is not a failure but a synthesis case
        // below (the no-vanishing rule must add material, never subtract it).
        List<Candidate.BeforeItem> beforeItems = candidate.beforeItems();
        List<String> issuedIds = beforeItems.stream().map(Candidate.BeforeItem::id).toList();
        // The last attempt that PASSED the hard guardrails, kept even when a
        // later attempt fails them: a coverage re-ask that comes back broken
        // must not discard the persistable draft it was trying to improve —
        // the gap synthesis below exists precisely to salvage it.
        AIResponse<ShiftNarrativeResult> response = null;
        ShiftNarrativeResult result = null;
        Optional<NarrativeGuardrails.Rejection> rejection = Optional.empty();
        List<String> uncovered = List.of();
        String correction = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            AIResponse<ShiftNarrativeResult> attemptResponse =
                    chat.generateShiftNarrative(userMessage, correction, metadata);
            rejection = NarrativeGuardrails.validate(attemptResponse.parsed(), decline);
            if (rejection.isPresent()) {
                correction = NarrativeGuardrails.correction(
                        rejection.get(), ctx.wording().declineCloseInstruction());
                log.info("Shift narrative rejected ({}) for pillar '{}' on attempt {}",
                        rejection.get(), candidate.pillar().getPillarNameSnapshot(), attempt);
                continue;
            }
            response = attemptResponse;
            result = attemptResponse.parsed();
            uncovered = NarrativeGuardrails.uncoveredBeforeIds(result, issuedIds);
            if (uncovered.isEmpty()) {
                break;
            }
            correction = NarrativeGuardrails.coverageCorrection(uncovered);
            log.info("Shift narrative for pillar '{}' left {} BEFORE item(s) uncovered on attempt {}",
                    candidate.pillar().getPillarNameSnapshot(), uncovered.size(), attempt);
        }
        if (result == null) {
            log.warn("Shift narrative for pillar '{}' failed the {} guardrail {} times — not persisted",
                    candidate.pillar().getPillarNameSnapshot(), rejection.get(), MAX_ATTEMPTS);
            return new Generation(null, rejection.get());
        }

        OpenRouterChatService.Provenance provenance = response.provenance();
        // Regeneration reuses the row (the V169 unique key forbids a second one)
        // and resets its review state: the new prose has never been signed off,
        // so the approval/edit stamps go, and the legacy kind/body pair is
        // cleared — the fresh items are the whole breakdown. The auto-approve
        // branch below still applies, exactly as it does to a first generation.
        ShiftNarrative narrative = existing != null ? existing : new ShiftNarrative();
        if (existing != null) {
            narrative.setStatus(NarrativeStatus.DRAFT);
            narrative.setApprovedBy(null);
            narrative.setApprovedAt(null);
            narrative.setEditedBy(null);
            narrative.setEditedAt(null);
            narrative.setKind(null);
            narrative.setBody(null);
        }
        narrative.setCohortId(ctx.comparison().getCohortId());
        narrative.setOrgId(ctx.comparison().getOrgId());
        narrative.setUserId(ctx.comparison().getUserId());
        narrative.setBaselinePillarId(candidate.pillar().getBaselinePillarId());
        narrative.setDistancePillarId(candidate.distancePillarId());
        narrative.setPillarNameSnapshot(candidate.pillar().getPillarNameSnapshot());
        // kind/body stay NULL: this row is born in the §2 breakdown shape, and
        // back-filling the legacy pair from the first item would invent a
        // "primary" observation the model never nominated.
        String reason = needsAttentionReason(candidate.activity());
        List<ShiftNarrative.Item> items = new ArrayList<>(items(result, reason));
        boolean coverageGap = !uncovered.isEmpty();
        if (coverageGap) {
            // The no-vanishing default: every still-uncovered baseline item gets
            // a code-synthesised fallback — a strength with no later evidence is
            // FADED, a growth edge PERSISTED — and the row is flagged for the
            // coach. Quoting the founder's own line verbatim is what makes the
            // fallback reviewable rather than a bare apology.
            Map<String, Candidate.BeforeItem> byId = beforeItems.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Candidate.BeforeItem::id, java.util.function.Function.identity()));
            for (String id : uncovered) {
                Candidate.BeforeItem before = byId.get(id);
                NarrativeKind kind = before.working() ? NarrativeKind.FADED : NarrativeKind.PERSISTED;
                // "flagged for your coach to review" was retired from this
                // copy: by the time a founder reads it the coach HAS reviewed
                // (the gap holds the row in DRAFT), so the sentence read stale.
                items.add(new ShiftNarrative.Item(kind,
                        "Your first assessment noted \"" + before.text().trim() + "\". The later "
                                + "reading does not clearly account for this, so it is worth "
                                + "revisiting with your coach.",
                        kind == NarrativeKind.FADED ? reason : null));
            }
            log.info("Shift narrative for pillar '{}' synthesised {} fallback item(s) for "
                            + "uncovered BEFORE items — flagged for review",
                    candidate.pillar().getPillarNameSnapshot(), uncovered.size());
        }
        narrative.setItems(items);
        narrative.setCoverageGap(coverageGap);
        narrative.setClosingAction(result.closingAction().isBlank() ? null : result.closingAction().trim());
        narrative.setBandKey(bandKey);
        narrative.setDecline(decline);
        narrative.setGeneratedAt(Instant.now());
        narrative.setAiModelUsed(provenance.model());
        narrative.setAiTemperature(provenance.temperature());
        narrative.setAiPromptVersionId(provenance.systemPromptVersionId());
        narrative.setConfigSnapshot(new ShiftNarrative.Snapshot(
                ctx.wording(), bandKey, candidate.pillar().getBandLabel()));
        if (ctx.wording().autoApprove() && !coverageGap) {
            // §6 auto-approve: stamped by the SYSTEM — approvedBy stays null so
            // the UI never attributes a machine decision to a person. A coverage
            // gap always holds the row in DRAFT: its synthesised fallback items
            // exist precisely so a coach looks at them before a founder does.
            narrative.setStatus(NarrativeStatus.APPROVED);
            narrative.setApprovedAt(Instant.now());
        }
        return new Generation(narratives.save(narrative), null);
    }

    /**
     * The guardrail already proved every kind parses, so {@code orElseThrow}
     * cannot fire. The needs-attention kinds (REGRESSED, FADED — what the
     * founder view renders as "Needs attention") get the deterministic
     * tracking-data reason attached; it is code-written from the pillar's
     * tagged work, never the model's to invent (second reading redesign).
     */
    private static List<ShiftNarrative.Item> items(ShiftNarrativeResult result, String reason) {
        return result.items().stream()
                .map(i -> {
                    NarrativeKind kind = NarrativeKind.parse(i.kind()).orElseThrow();
                    boolean needsAttention =
                            kind == NarrativeKind.REGRESSED || kind == NarrativeKind.FADED;
                    return new ShiftNarrative.Item(kind, i.text().trim(),
                            needsAttention ? reason : null);
                })
                .toList();
    }

    /**
     * The needs-attention reason box, written by CODE from the pillar's tagged
     * practice work — never invented, per the second reading redesign: either
     * the tracking data explains the change, or the card falls back to the
     * honest "no clear reason" sentence (a null here).
     *
     * <p>Only INCOMPLETE practice can explain a slip, so fully-completed work —
     * or a pillar with no tagged work at all — returns null rather than a
     * reason that explains nothing. Workshop attendance and coach notes join
     * here once they carry a pillar link; today neither does.
     */
    static String needsAttentionReason(List<NarrativeActivityRepository.TaskActivity> activity) {
        if (activity.isEmpty()) {
            return null;
        }
        LocalDate today = LocalDate.now();
        long overdue = activity.stream().filter(t -> "overdue".equals(t.status(today))).count();
        long pending = activity.stream().filter(t -> "pending".equals(t.status(today))).count();
        long open = overdue + pending;
        if (open == 0) {
            return null;
        }
        int total = activity.size();
        StringBuilder sb = new StringBuilder();
        sb.append(open).append(" of the ").append(total)
                .append(total == 1 ? " practice task" : " practice tasks")
                .append(" tied to this pillar ").append(open == 1 ? "is" : "are")
                .append(" still open");
        if (overdue > 0) {
            sb.append(" (").append(overdue).append(" overdue)");
        }
        Optional<Instant> lastDone = activity.stream()
                .map(NarrativeActivityRepository.TaskActivity::completedAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder());
        if (lastDone.isPresent()) {
            sb.append("; practice was last completed on ")
                    .append(lastDone.get().atZone(ZoneId.systemDefault()).toLocalDate());
        } else {
            sb.append("; no practice has been completed on this pillar yet");
        }
        return sb.append('.').toString();
    }

    /**
     * The user message: pillar name, the four text blocks, and the programme
     * work tagged to this pillar. Deliberately built from {@link Candidate},
     * which carries no score.
     */
    static String userMessage(Candidate candidate) {
        StringBuilder sb = new StringBuilder();
        sb.append("PILLAR: ").append(candidate.pillar().getPillarNameSnapshot()).append('\n');
        sb.append("PILLAR DIRECTION: ").append(candidate.direction()).append('\n');
        // The before/after percentages, as CONTEXT for gauging the size of the
        // shift (operator decision 2026-08-19 — a direction word alone cannot
        // distinguish +2 from +40). Repeating them in the output is allowed
        // since 2026-08-20 (V205).
        if (candidate.pillar().getBeforePct() != null && candidate.pillar().getAfterPct() != null) {
            sb.append("PILLAR SCORE: before ").append(pct(candidate.pillar().getBeforePct()))
                    .append("% → after ").append(pct(candidate.pillar().getAfterPct()))
                    .append("%\n");
        }
        sb.append('\n');
        // BEFORE items are NUMBERED (B1…): the ids are what the coverage audit
        // checks the model's "covers" declarations against, so the no-vanishing
        // rule is verifiable in code rather than hoped for in prose.
        List<Candidate.BeforeItem> beforeItems = candidate.beforeItems();
        appendNumberedBlock(sb, "BEFORE — what's working",
                beforeItems.stream().filter(Candidate.BeforeItem::working).toList());
        appendNumberedBlock(sb, "BEFORE — what can improve",
                beforeItems.stream().filter(b -> !b.working()).toList());
        appendBlock(sb, "AFTER — what's working", candidate.afterWorking());
        appendBlock(sb, "AFTER — what can improve", candidate.afterImprove());
        appendActivity(sb, candidate.activity());
        sb.append(RESOLVED_NOTE);
        sb.append(COVERAGE_NOTE);
        if (candidate.afterTextMissing()) {
            sb.append(MISSING_AFTER_NOTE);
        }
        return sb.toString();
    }

    /**
     * The "nothing disappears" rule (second reading redesign, Aug 2026), riding
     * every call from Java for the same reason {@link #RESOLVED_NOTE} does: a
     * guarded migration cannot reach admin-customised templates, and this is the
     * half that must not be optional. The code side of the contract is
     * {@link NarrativeGuardrails#uncoveredBeforeIds}.
     */
    private static final String COVERAGE_NOTE = """
            RULE: every numbered BEFORE item (B1, B2, …) must be accounted for. Give each \
            entry in "items" a "covers" array naming the BEFORE ids that observation speaks \
            to (for example "covers": ["B1", "B3"]); an observation with no earlier \
            counterpart (NEW, EMERGED) uses an empty array. Nothing from the first \
            assessment may silently disappear: a BEFORE item with no clear later evidence \
            is still classified — FADED for a strength, PERSISTED for a growth edge.
            """;

    /**
     * V203's half of the RESOLVED narrowing, in code for the same reason
     * {@link #MISSING_AFTER_NOTE} is: the guarded migration cannot touch
     * admin-customised templates, and "absence is not resolution" is the half
     * that must not be optional — the founder view celebrates RESOLVED as a
     * closed gap, so it may only ever mean growth edge → strength.
     */
    private static final String RESOLVED_NOTE = """
            RULE: RESOLVED means the growth edge now shows as a strength in the AFTER text \
            or the ACTIVITY section. A growth edge that is simply absent from AFTER is \
            PERSISTED, not RESOLVED — absence of mention is not resolution.
            """;

    /**
     * The other half of the empty-AFTER guardrail. A pillar only reaches the
     * model with no later assessment text when tagged work makes up for it
     * ({@link Candidate#hasEnoughMaterial()}), and left to itself the model reads
     * an empty AFTER block as "the founder lost all of it": every baseline item
     * comes back FADED or RESOLVED, confidently, on no evidence.
     *
     * <p>The template carries the standing version of this rule (V192's MISSING
     * MATERIAL block); this is the conditional, sharper half, and it lives in
     * code for two reasons. It fires only on the calls it applies to, right after
     * the activity it is about. And it binds on installations whose admins have
     * customised their own SHIFT_NARRATIVE prompt — a guarded migration
     * deliberately cannot touch those, and this is the half that must not be
     * optional. The two agree; the repetition is emphasis, not drift.
     */
    private static final String MISSING_AFTER_NOTE = """
            NOTE: the later assessment recorded no commentary for this pillar. That is a gap in \
            the record, not evidence the founder lost anything — do NOT classify anything as \
            FADED or RESOLVED on that basis. Ground every observation in the ACTIVITY section \
            above and classify it against the BEFORE text: PERSISTED where the work still shows \
            a growth edge named before, CARRIED_FORWARD where it still shows a strength named \
            before, NEW where it shows something the BEFORE text has no equivalent for.
            """;

    /**
     * Spec §2's activity section: every tagged task, the member's status on it,
     * and the FULL submitted content — no truncation and no item cap, which is
     * the decision, not an oversight.
     *
     * <p>Both halves are FENCED in tags the prompt names: the founder's own work
     * in {@code <submission>}, staff notes in {@code <facilitator_note>}. Labels
     * alone were not enough — a label prefixes the first physical line, and a
     * submission is free text that can contain newlines, so a founder could type
     * a line that looked exactly like one of ours. A tag pair delimits the whole
     * block however many lines it runs to, and {@link #fenced} strips the tags
     * out of the data so nothing inside can close its own fence.
     */
    private static void appendActivity(StringBuilder sb,
                                       List<NarrativeActivityRepository.TaskActivity> activity) {
        if (activity.isEmpty()) {
            return;
        }
        LocalDate today = LocalDate.now();
        sb.append("ACTIVITY — programme work tagged to this pillar:\n\n");
        for (NarrativeActivityRepository.TaskActivity task : activity) {
            sb.append("TASK: ").append(task.taskName())
                    .append(" (").append(task.taskType()).append(')')
                    .append(" — status: ").append(task.status(today));
            if (task.dueDate() != null) {
                sb.append(", due ").append(task.dueDate());
            }
            if (task.completedAt() != null) {
                // A date, not the raw instant — no narrative has ever needed
                // the seconds.
                sb.append(", completed ")
                        .append(task.completedAt().atZone(ZoneId.systemDefault()).toLocalDate());
            }
            sb.append('\n');
            if (task.aiContext() != null && !task.aiContext().isBlank()) {
                // The admin's staff-only brief on what the task is for. Fenced
                // for the same reason the member data is: it is interpolated
                // text, and the fence is what makes "data, not instructions"
                // followable rather than aspirational.
                sb.append("  ABOUT: ").append(fenced(task.aiContext()).strip().replace('\n', ' '))
                        .append('\n');
            }
            if (task.content().isEmpty()) {
                sb.append("  (nothing submitted)\n");
            } else {
                sb.append("  <submission>\n");
                task.content().forEach(line -> sb.append("  ").append(fenced(line)).append('\n'));
                sb.append("  </submission>\n");
            }
            task.facilitatorNotes().forEach(note -> sb
                    .append("  <facilitator_note>\n  ").append(fenced(note))
                    .append("\n  </facilitator_note>\n"));
            sb.append('\n');
        }
    }

    /**
     * The fence tags themselves, stripped out of whatever goes INSIDE a fence.
     * Everything in a {@code <submission>} is data, and a founder who typed
     * "&lt;/submission&gt;" would otherwise be writing prompt structure the model
     * reads as ours. The tags are the assembler's to emit, so removing them from
     * the data costs nothing anybody would miss.
     */
    private static final Pattern FENCE_TAG = Pattern.compile(
            "</?\\s*(?:submission|facilitator_note)\\s*>", Pattern.CASE_INSENSITIVE);

    /**
     * "44.00" → "44", "56.50" → "56.5" — the prompt reads like a person wrote
     * it. ONE definition for the package: {@link MemberGrowthSummaryService}
     * builds the same score context into its own prompt, and the two must not
     * be able to drift into formatting a percentage differently for the model.
     */
    static String pct(java.math.BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String fenced(String text) {
        return FENCE_TAG.matcher(text).replaceAll("");
    }

    /** {@link #appendBlock}, but each line carries its coverage id: {@code - B3: …}. */
    private static void appendNumberedBlock(StringBuilder sb, String title,
                                            List<Candidate.BeforeItem> items) {
        sb.append(title).append(":\n");
        if (items.isEmpty()) {
            sb.append("- (none recorded)\n");
        } else {
            items.forEach(item -> sb.append("- ").append(item.id()).append(": ")
                    .append(item.text()).append('\n'));
        }
        sb.append('\n');
    }

    private static void appendBlock(StringBuilder sb, String title, List<String> lines) {
        sb.append(title).append(":\n");
        if (lines == null || lines.isEmpty()) {
            sb.append("- (none recorded)\n");
        } else {
            lines.forEach(line -> sb.append("- ").append(line).append('\n'));
        }
        sb.append('\n');
    }

    /* ====================================================== the read side */

    /**
     * The staff review payload: drafts AND approved, plus what can still be
     * generated. Anchored on the member's own comparison cohort — the same
     * anchor {@code ComparisonQueryService.myComparison} uses, so the review
     * list and the Growth tab's table can never describe different cohorts.
     */
    @Transactional(readOnly = true)
    public NarrativeReviewResponse review(UUID userId) {
        UUID cohortId = anchorCohort(userId);
        Context ctx = context(cohortId, userId);
        List<ShiftNarrative> rows = narratives.findByCohortIdAndUserId(cohortId, userId);
        Map<UUID, String> actorNames = names(rows);
        Set<UUID> withNarrative = rows.stream()
                .map(ShiftNarrative::getDistancePillarId).collect(java.util.stream.Collectors.toSet());

        List<NarrativePillarOption> available = new ArrayList<>();
        List<NarrativePillarOption> insufficient = new ArrayList<>();
        Set<UUID> live = new HashSet<>();
        for (Candidate candidate : ctx.candidates()) {
            live.add(candidate.distancePillarId());
            if (withNarrative.contains(candidate.distancePillarId())) {
                continue;
            }
            NarrativePillarOption option = new NarrativePillarOption(candidate.distancePillarId(),
                    candidate.pillar().getPillarNameSnapshot());
            (candidate.hasEnoughMaterial() ? available : insufficient).add(option);
        }

        return new NarrativeReviewResponse(
                rows.stream().sorted(NARRATIVE_ORDER)
                        .map(n -> toDto(n, actorNames, !live.contains(n.getDistancePillarId())))
                        .toList(),
                available, insufficient,
                ctx.wording().notEnoughDataSentence(), ctx.wording().autoApprove(),
                ctx.comparison().getComputedAt());
    }

    /**
     * APPROVED, still-attached narratives — the single door the member's My
     * Growth, the PDF and the Excel read through. Returns an empty list (never
     * null) so the report payload's shape is stable in every lifecycle state.
     *
     * @param mappedDistancePillarIds the distance pillars the CURRENT comparison
     *        actually measures. A super admin can remap a pillar pair, after
     *        which a narrative's pillar is no longer part of the report — the
     *        prose survives for staff to read and delete, but it must not keep
     *        appearing beside a table that no longer contains its pillar. The
     *        caller already has this set in hand, so this costs no extra query.
     */
    @Transactional(readOnly = true)
    public List<ShiftNarrativeDto> approvedFor(UUID cohortId, UUID userId,
                                               Set<UUID> mappedDistancePillarIds) {
        List<ShiftNarrative> rows = narratives.findByCohortIdAndUserIdAndStatus(
                        cohortId, userId, NarrativeStatus.APPROVED).stream()
                .filter(n -> mappedDistancePillarIds.contains(n.getDistancePillarId()))
                .toList();
        Map<UUID, String> actorNames = names(rows);
        return rows.stream().sorted(NARRATIVE_ORDER)
                .map(n -> toDto(n, actorNames, false)).toList();
    }

    /**
     * How many narratives the founder cannot see yet (§6 review gate) — a COUNT,
     * never content, so the member surface can say "3 more are being reviewed"
     * without leaking a single unapproved word. Detached drafts are excluded:
     * they are not coming.
     */
    @Transactional(readOnly = true)
    public int draftCountFor(UUID cohortId, UUID userId, Set<UUID> mappedDistancePillarIds) {
        return (int) narratives.findByCohortIdAndUserIdAndStatus(
                        cohortId, userId, NarrativeStatus.DRAFT).stream()
                .filter(n -> mappedDistancePillarIds.contains(n.getDistancePillarId()))
                .count();
    }

    /** Approved first, then by generation time — the review list's reading order. */
    private static final Comparator<ShiftNarrative> NARRATIVE_ORDER =
            Comparator.comparing((ShiftNarrative n) -> n.getStatus() != NarrativeStatus.APPROVED)
                    .thenComparing(ShiftNarrative::getGeneratedAt,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    /* ==================================================== the review gate */

    /**
     * Publishes a narrative to the founder — the ONE gate every surface reads
     * through, so §6's decline rule is re-asserted HERE and not only on the
     * write paths that happen to precede it. A decline that lost its next step
     * (a band widened by a recompute, a draft imported before the rule existed)
     * can therefore never reach APPROVED through any door.
     *
     * <p>Idempotent: re-approving an APPROVED narrative keeps the ORIGINAL
     * stamp — "who signed this off, and when" must not drift on a double click.
     */
    @Transactional
    public ShiftNarrativeDto approve(UUID userId, UUID narrativeId, UUID actorId) {
        ShiftNarrative narrative = requireOwned(userId, narrativeId);
        if (narrative.getStatus() == NarrativeStatus.APPROVED) {
            return toDto(narrative, names(List.of(narrative)), false);
        }
        requireClosingActionIfDeclining(narrative);
        narrative.setStatus(NarrativeStatus.APPROVED);
        narrative.setApprovedBy(actorId);
        narrative.setApprovedAt(Instant.now());
        auditLogger.log(actorId, narrative.getOrgId(), "SHIFT_NARRATIVE_APPROVED", "ShiftNarrative",
                narrativeId, Map.of("pillar", narrative.getPillarNameSnapshot()));
        return toDto(narratives.save(narrative), names(List.of(narrative)), false);
    }

    /**
     * A reviewer's edit. An edit of an APPROVED narrative returns it to DRAFT
     * and clears the approval stamp: the approved text is what the member was
     * signed off to see, so changed prose has to be signed off again — and a
     * stale "Approved Oct 3 · …" line on text written on Oct 9 would be a lie
     * the §7b stamping rules exist to prevent.
     */
    @Transactional
    public ShiftNarrativeDto update(UUID userId, UUID narrativeId,
                                    UpdateNarrativeRequest request, UUID actorId) {
        ShiftNarrative narrative = requireOwned(userId, narrativeId);
        // The reason box is CODE-computed from tracking data at generation and
        // is per-pillar (every needs-attention item of a row carries the same
        // one). It travels through an edit from the ROW, never from the request
        // — accepting the client's string would let a reviewer fabricate a
        // "based on your tracking data" claim.
        String reason = narrative.getItems() == null ? null
                : narrative.getItems().stream()
                        .map(ShiftNarrative.Item::reason)
                        .filter(r -> r != null && !r.isBlank())
                        .findFirst().orElse(null);
        narrative.setItems(request.items().stream()
                .map(i -> {
                    NarrativeKind kind = NarrativeKind.parse(i.kind())
                            .orElseThrow(() -> new BadRequestException(
                                    "Unknown narrative kind: " + i.kind()));
                    boolean needsAttention =
                            kind == NarrativeKind.REGRESSED || kind == NarrativeKind.FADED;
                    return new ShiftNarrative.Item(kind, i.text().trim(),
                            needsAttention ? reason : null);
                })
                .toList());
        // A legacy row edited here MIGRATES: the reviewer just rewrote the whole
        // breakdown, so leaving the old paragraph beside it would be two
        // sources of truth on one row, one of them stale.
        narrative.setKind(null);
        narrative.setBody(null);
        String closing = request.closingAction() == null ? null : request.closingAction().trim();
        narrative.setClosingAction(closing == null || closing.isBlank() ? null : closing);
        // The §6 decline rule binds humans too — a reviewer must not be able to
        // hand-edit the way forward off a declining pillar.
        requireClosingActionIfDeclining(narrative);
        narrative.setEditedBy(actorId);
        narrative.setEditedAt(Instant.now());
        narrative.setStatus(NarrativeStatus.DRAFT);
        narrative.setApprovedBy(null);
        narrative.setApprovedAt(null);
        auditLogger.log(actorId, narrative.getOrgId(), "SHIFT_NARRATIVE_EDITED", "ShiftNarrative",
                narrativeId, Map.of("pillar", narrative.getPillarNameSnapshot()));
        return toDto(narratives.save(narrative), names(List.of(narrative)), false);
    }

    /** Discard a draft. Approved narratives are not deletable — they are edited (back to draft) first. */
    @Transactional
    public void delete(UUID userId, UUID narrativeId, UUID actorId) {
        ShiftNarrative narrative = requireOwned(userId, narrativeId);
        if (narrative.getStatus() == NarrativeStatus.APPROVED) {
            throw new BadRequestException(
                    "Approved narratives cannot be deleted. Edit it first — that returns it to draft.");
        }
        narratives.delete(narrative);
        auditLogger.log(actorId, narrative.getOrgId(), "SHIFT_NARRATIVE_DELETED", "ShiftNarrative",
                narrativeId, Map.of("pillar", narrative.getPillarNameSnapshot()));
    }

    /**
     * §7: "Approved narratives never auto-recompute (recompute returns them to
     * draft)." Called from {@code ComparisonComputeService.recomputeCohort} —
     * NOT a cascade, because the narrative is anchored to the cohort + pillar,
     * not to the comparison row the recompute rebuilds.
     */
    public int revertApprovalsForCohort(UUID cohortId) {
        int reverted = narratives.revertApprovalsForCohort(cohortId);
        if (reverted > 0) {
            log.info("Recompute returned {} approved shift narrative(s) to draft for cohort {}",
                    reverted, cohortId);
        }
        return reverted;
    }

    /** Org-scoped {@link #revertApprovalsForCohort}: only the given members' narratives. */
    public int revertApprovalsForCohortMembers(UUID cohortId, java.util.Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return 0;
        }
        int reverted = narratives.revertApprovalsForCohortMembers(cohortId, userIds);
        if (reverted > 0) {
            log.info("Recompute returned {} approved shift narrative(s) to draft for cohort {} "
                    + "({} member(s))", reverted, cohortId, userIds.size());
        }
        return reverted;
    }

    /**
     * The other half of the recompute seam: re-derive each narrative's decline
     * flag and band label from the freshly rebuilt pillar rows, so an edited
     * band config actually binds the guardrail and the chips. Must run AFTER the
     * rebuild.
     */
    public int restampBandsForCohort(UUID cohortId) {
        return narratives.restampBandsForCohort(cohortId);
    }

    /** Org-scoped {@link #restampBandsForCohort}: only the given members' narratives. */
    public int restampBandsForCohortMembers(UUID cohortId, java.util.Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return 0;
        }
        return narratives.restampBandsForCohortMembers(cohortId, userIds);
    }

    /* ---------------------------------------------------------- plumbing */

    /**
     * Tenancy guard: the narrative must belong to the founder the caller was
     * already authorized for (org membership for an admin, {@code CoachAccess}
     * for a coach), so a valid id from another founder — or another org — is a
     * 404, not an edit.
     */
    /**
     * §6's decline rule, in the one place every write path passes through.
     * Declining pillars are identified by the STAMPED boolean, so a renamed or
     * deleted band cannot switch the rule off.
     */
    private static void requireClosingActionIfDeclining(ShiftNarrative narrative) {
        if (NarrativeGuardrails.requiresClosingAction(narrative.isDecline())
                && (narrative.getClosingAction() == null || narrative.getClosingAction().isBlank())) {
            throw new BadRequestException(
                    "A declining pillar's narrative must keep a forward-looking next step.");
        }
    }

    private ShiftNarrative requireOwned(UUID userId, UUID narrativeId) {
        return narratives.findById(narrativeId)
                .filter(n -> n.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Shift narrative", narrativeId.toString()));
    }

    /**
     * The member's comparison cohort — the newest cohort with a fully designated
     * pair, exactly as the growth read resolves it. Package-private rather than
     * private so {@link MemberGrowthSummaryService} anchors on the SAME cohort
     * instead of resolving its own.
     */
    UUID anchorCohort(UUID userId) {
        return reads.memberPairCohort(userId)
                .map(ComparisonReadRepository.PairCohortRow::cohortId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Designated comparison pair for member", userId.toString()));
    }

    /** Everything one founder's narrative work needs, read once. */
    record Context(FounderComparison comparison, NarrativeWording wording,
                   List<Candidate> candidates, Map<UUID, ShiftNarrative> existingByPillar) {

        Set<UUID> existingPillarIds() {
            return existingByPillar.keySet();
        }
    }

    /** A mapped pillar with both sides' text and its tagged work in hand — a narrative candidate. */
    record Candidate(FounderComparisonPillar pillar, List<String> beforeWorking,
                     List<String> beforeImprove, List<String> afterWorking, List<String> afterImprove,
                     List<NarrativeActivityRepository.TaskActivity> activity) {

        UUID distancePillarId() {
            return pillar.getDistancePillarId();
        }

        /** One numbered baseline observation — the unit the no-vanishing rule audits. */
        record BeforeItem(String id, String text, boolean working) {
        }

        /**
         * The baseline text as numbered items ({@code B1}…), working block first
         * — the SAME order {@link #userMessage} prints them in, which is what
         * makes the ids the model echoes back auditable against this list.
         */
        List<BeforeItem> beforeItems() {
            List<BeforeItem> out = new ArrayList<>();
            for (String line : beforeWorking) {
                out.add(new BeforeItem("B" + (out.size() + 1), line, true));
            }
            for (String line : beforeImprove) {
                out.add(new BeforeItem("B" + (out.size() + 1), line, false));
            }
            return out;
        }

        /**
         * Which way the pillar moved, as a WORD. The prompt makes the closing
         * action MANDATORY on a decline, and until now the model was never told
         * whether the pillar had declined — an unanswerable rule that
         * {@link NarrativeGuardrails.Rejection#MISSING_CLOSING_ACTION} then
         * punished it for missing. Read from the same {@code signum()} the
         * stamped decline flag uses, so the prompt and the guardrail can never
         * disagree.
         */
        String direction() {
            return switch (pillar.getDelta().signum()) {
                case -1 -> "declined";
                case 1 -> "improved";
                default -> "held steady";
            };
        }

        /**
         * Both floors: a "before" to compare from, and something — later
         * assessment text or programme work tagged to the pillar — to compare it
         * against. ONE predicate rather than two call-site conjunctions, because
         * the batch job, the on-demand button, the review list and
         * {@link MemberGrowthSummaryService}'s "which pillars must be approved
         * first" rule all have to answer this identically; a pillar that can
         * never generate must never be one the summary waits for.
         */
        boolean hasEnoughMaterial() {
            return NarrativeGuardrails.hasEnoughBeforeText(beforeWorking, beforeImprove)
                    && NarrativeGuardrails.hasComparableAfter(
                            afterWorking, afterImprove, !activity.isEmpty());
        }

        /**
         * True when the later assessment recorded nothing and the tagged work is
         * the only "after" in hand — the case
         * {@link ShiftNarrativeService#MISSING_AFTER_NOTE} exists for.
         */
        boolean afterTextMissing() {
            return !NarrativeGuardrails.hasAfterText(afterWorking, afterImprove);
        }
    }

    /**
     * Everything one founder's narrative work needs, read once. Package-private:
     * {@link MemberGrowthSummaryService} reuses this candidate set as its "which
     * pillars must be approved first" rule, so the two screens cannot disagree
     * about which pillars are eligible.
     */
    Context context(UUID cohortId, UUID userId) {
        FounderComparison comparison = comparisons.findByCohortIdAndUserId(cohortId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Comparison for user", userId.toString()));

        Map<UUID, RawPillarText> before = reads.pillarTextBlocks(comparison.getBaselineSubmissionId());
        Map<UUID, RawPillarText> after = reads.pillarTextBlocks(comparison.getDistanceSubmissionId());
        // Every pillar's tagged work in ONE read: generation loops a model call
        // per pillar, and per-pillar activity queries would multiply the
        // round-trips by the pillar count for nothing.
        Map<UUID, List<NarrativeActivityRepository.TaskActivity>> activity =
                activityReads.activityByPillar(cohortId, userId);

        List<Candidate> candidates = comparisonPillars.findByComparisonId(comparison.getId()).stream()
                // Only MAPPED rows can carry a narrative: a newly-measured pillar
                // has no "before" and a not-re-measured one has no "after", so
                // there is no shift to describe (§5).
                .filter(p -> p.getState() == PillarComparisonState.MAPPED
                        && p.getDelta() != null
                        && p.getBaselinePillarId() != null && p.getDistancePillarId() != null)
                .map(p -> new Candidate(p,
                        lines(before.get(p.getBaselinePillarId()), true),
                        lines(before.get(p.getBaselinePillarId()), false),
                        lines(after.get(p.getDistancePillarId()), true),
                        lines(after.get(p.getDistancePillarId()), false),
                        activity.getOrDefault(p.getDistancePillarId(), List.of())))
                .toList();

        // Keyed rows, not just ids: regeneration overwrites the existing row in
        // place (the V169 unique constraint forbids a second one). Uniqueness of
        // the key is that same constraint's guarantee.
        Map<UUID, ShiftNarrative> existing = narratives.findByCohortIdAndUserId(cohortId, userId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ShiftNarrative::getDistancePillarId, java.util.function.Function.identity()));
        return new Context(comparison, wording(), candidates, existing);
    }

    /** The §7 narrative wording document, read by SQL (no comparison→platform import). */
    NarrativeWording wording() {
        return reads.settingText(NarrativeWording.NARRATIVE_KEY)
                .flatMap(json -> {
                    try {
                        return Optional.of(MAPPER.readValue(json, NarrativeWording.class));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        log.warn("Stored narrative wording is unparseable; using defaults: {}",
                                e.getOriginalMessage());
                        return Optional.<NarrativeWording>empty();
                    }
                })
                .orElseGet(NarrativeWording::defaults)
                .withDefaults();
    }

    /**
     * jsonb string array → list; an absent row or unreadable value reads as "no
     * text". Package-private so {@link CohortGrowthSummaryService} parses the
     * same columns the same way instead of forking a second reader.
     */
    static List<String> lines(RawPillarText row, boolean working) {
        if (row == null) {
            return List.of();
        }
        String json = working ? row.whatsWorkingJson() : row.whatCanImproveJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = MAPPER.readValue(json, STRING_LIST);
            return parsed == null ? List.of() : parsed.stream().filter(s -> s != null && !s.isBlank()).toList();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return List.of();
        }
    }

    /** Reviewer names for the §7b stamps; erased reviewers simply drop out of the map. */
    private Map<UUID, String> names(List<ShiftNarrative> rows) {
        Set<UUID> ids = new HashSet<>();
        rows.forEach(n -> {
            if (n.getApprovedBy() != null) ids.add(n.getApprovedBy());
            if (n.getEditedBy() != null) ids.add(n.getEditedBy());
        });
        return ids.isEmpty() ? Map.of() : reads.userNames(ids);
    }

    /**
     * Both shapes travel: {@code items} for anything written since V189,
     * {@code kind}/{@code body} for the rows that predate it. Never both — so
     * readers fall back on the pair only when {@code items} is null.
     */
    private static ShiftNarrativeDto toDto(ShiftNarrative n, Map<UUID, String> actorNames,
                                           boolean detached) {
        List<ShiftNarrativeDto.NarrativeItemDto> items = n.getItems() == null ? null
                : n.getItems().stream()
                        .map(i -> new ShiftNarrativeDto.NarrativeItemDto(
                                i.kind().name(), i.text(), i.reason()))
                        .toList();
        return new ShiftNarrativeDto(n.getId(), n.getBaselinePillarId(), n.getDistancePillarId(),
                n.getPillarNameSnapshot(), items,
                n.getKind() == null ? null : n.getKind().name(), n.getBody(), n.getClosingAction(),
                n.isDecline(), detached,
                n.getBandKey(), n.isCoverageGap(), n.getStatus().name(), n.getGeneratedAt(),
                n.getApprovedBy(), nameOf(actorNames, n.getApprovedBy()), n.getApprovedAt(),
                n.getEditedBy(), nameOf(actorNames, n.getEditedBy()), n.getEditedAt());
    }

    /**
     * Null-safe name lookup. The actor id is null on a draft (never approved) and
     * on an auto-approved narrative (no human), and {@code Map.of()} — what
     * {@link #names} returns when nothing needs looking up — throws on a null
     * key rather than answering "absent".
     */
    private static String nameOf(Map<UUID, String> actorNames, UUID actorId) {
        return actorId == null ? null : actorNames.get(actorId);
    }
}
