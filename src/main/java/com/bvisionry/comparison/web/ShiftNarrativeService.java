package com.bvisionry.comparison.web;

import com.bvisionry.aicalllog.dto.CallMetadata;
import com.bvisionry.aiconfig.service.OpenRouterChatService;
import com.bvisionry.aiconfig.service.OpenRouterChatService.AIResponse;
import com.bvisionry.common.audit.AuditLogger;
import com.bvisionry.common.dto.ShiftNarrativeResult;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.scoringconfig.NarrativeWording;
import com.bvisionry.comparison.domain.FounderComparison;
import com.bvisionry.comparison.domain.FounderComparisonPillar;
import com.bvisionry.comparison.domain.NarrativeKind;
import com.bvisionry.comparison.domain.NarrativeStatus;
import com.bvisionry.comparison.domain.PillarComparisonState;
import com.bvisionry.comparison.domain.ShiftNarrative;
import com.bvisionry.comparison.dto.NarrativeReviewResponse;
import com.bvisionry.comparison.dto.NarrativeReviewResponse.NarrativePillarOption;
import com.bvisionry.comparison.dto.NarrativeRequests.UpdateNarrativeRequest;
import com.bvisionry.comparison.dto.ShiftNarrativeDto;
import com.bvisionry.comparison.repository.ComparisonReadRepository;
import com.bvisionry.comparison.repository.ComparisonReadRepository.RawPillarText;
import com.bvisionry.comparison.repository.FounderComparisonPillarRepository;
import com.bvisionry.comparison.repository.FounderComparisonRepository;
import com.bvisionry.comparison.repository.ShiftNarrativeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The Qualitative Shift Narrative job + review gate (redesign spec §6).
 *
 * <h2>What the model sees</h2>
 * ONLY the pillar name and the two text blocks ("what's working" / "what can
 * improve") from the baseline and distance evaluations. "No generation from
 * scores alone" is enforced by construction: {@link ComparisonReadRepository
 * #pillarTextBlocks} does not select a score, so there is none in hand to leak
 * into the prompt.
 *
 * <h2>Guardrails (code, not prompt-hope)</h2>
 * See {@link NarrativeGuardrails}. Length floor → no model call at all and NO
 * row (the review UI shows the configured standard sentence inline for that
 * pillar; a row would imply a narrative somebody could approve). Decline
 * without a forward-looking close, or a kind outside the five → ONE corrective
 * retry, then a generation failure that persists nothing.
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
     * "Generate for the top 3–4 shift pillars" (§6) — the biggest-story pillars
     * by absolute shift, so the largest gains AND the largest declines both
     * qualify. 4, the top of the spec's range: the rest are one tap away via
     * on-demand generation, and under-generating costs a human a round trip
     * while over-generating costs one model call.
     */
    public static final int TOP_N = 4;

    /**
     * The model gets one draft and exactly one corrective re-ask (§6). A third
     * try would just re-bill the same misunderstanding: when a model cannot
     * classify into five kinds or close a decline after being told to, the
     * honest outcome is a generation failure a human can see.
     */
    static final int MAX_ATTEMPTS = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final ShiftNarrativeRepository narratives;
    private final FounderComparisonRepository comparisons;
    private final FounderComparisonPillarRepository comparisonPillars;
    private final ComparisonReadRepository reads;
    private final OpenRouterChatService chat;
    private final AuditLogger auditLogger;

    /* ======================================================== generation */

    /**
     * Fire-and-forget generation for the top-N shift pillars of a freshly
     * computed comparison. Runs on the evaluation executor so it never blocks
     * the compute transaction, and swallows everything: a narrative failure must
     * never fail — or un-compute — the comparison (§6).
     */
    @Async("evaluationExecutor")
    public void generateTopPillarsAsync(UUID cohortId, UUID userId) {
        try {
            generateTopPillars(cohortId, userId);
        } catch (RuntimeException e) {
            log.warn("Shift-narrative generation failed for cohort {} user {} "
                    + "(comparison unaffected): {}", cohortId, userId, e.getMessage(), e);
        }
    }

    /** Synchronous entry point — the async wrapper above, and the tests, call this. */
    public int generateTopPillars(UUID cohortId, UUID userId) {
        Context ctx = context(cohortId, userId);
        // Order matters: rank and CUT to the top N first, then drop the ones
        // that already have a narrative. Filtering first would let a second run
        // walk on down the list and narrate pillars 5-8 — the top N is the set
        // this is allowed to fill, so a re-run tops it up rather than extends it.
        List<Candidate> candidates = ctx.candidates().stream()
                .filter(Candidate::hasEnoughBeforeText)
                .sorted(Comparator.comparingDouble(
                        (Candidate c) -> Math.abs(c.pillar().getDelta().doubleValue())).reversed())
                .limit(TOP_N)
                .filter(c -> !ctx.existingPillarIds().contains(c.distancePillarId()))
                .toList();

        int generated = 0;
        for (Candidate candidate : candidates) {
            if (generateOne(ctx, candidate).isPresent()) {
                generated++;
            }
        }
        log.info("Generated {} of {} candidate shift narratives for cohort {} user {}",
                generated, candidates.size(), cohortId, userId);
        return generated;
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
        Context ctx = context(anchorCohort(userId), userId);
        if (ctx.existingPillarIds().contains(distancePillarId)) {
            throw new BadRequestException("This pillar already has a narrative.");
        }
        Candidate candidate = ctx.candidates().stream()
                .filter(c -> distancePillarId.equals(c.distancePillarId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Mapped comparison pillar", distancePillarId.toString()));
        if (!candidate.hasEnoughBeforeText()) {
            throw new BadRequestException(ctx.wording().notEnoughDataSentence());
        }
        ShiftNarrative saved = generateOne(ctx, candidate)
                .orElseThrow(() -> new BadRequestException(
                        "The narrative could not be generated for this pillar. Try again in a moment."));
        auditLogger.log(actorId, ctx.comparison().getOrgId(), "SHIFT_NARRATIVE_GENERATED",
                "ShiftNarrative", saved.getId(), Map.of("pillar", saved.getPillarNameSnapshot()));
        return toDto(saved, names(List.of(saved)), false);
    }

    /**
     * One model call plus, at most, one corrective retry. Returns empty when the
     * output still fails the guardrails — deliberately persisting NOTHING rather
     * than a decline with no way forward or an unclassifiable kind.
     */
    private Optional<ShiftNarrative> generateOne(Context ctx, Candidate candidate) {
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
        AIResponse<ShiftNarrativeResult> response = null;
        ShiftNarrativeResult result = null;
        Optional<NarrativeGuardrails.Rejection> rejection = Optional.empty();
        String correction = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            response = chat.generateShiftNarrative(userMessage, correction, metadata);
            result = response.parsed();
            rejection = NarrativeGuardrails.validate(result, decline);
            if (rejection.isEmpty()) {
                break;
            }
            correction = NarrativeGuardrails.correction(
                    rejection.get(), ctx.wording().declineCloseInstruction());
            log.info("Shift narrative rejected ({}) for pillar '{}' on attempt {}",
                    rejection.get(), candidate.pillar().getPillarNameSnapshot(), attempt);
        }
        if (rejection.isPresent()) {
            log.warn("Shift narrative for pillar '{}' failed the {} guardrail {} times — not persisted",
                    candidate.pillar().getPillarNameSnapshot(), rejection.get(), MAX_ATTEMPTS);
            return Optional.empty();
        }

        OpenRouterChatService.Provenance provenance = response.provenance();
        ShiftNarrative narrative = new ShiftNarrative();
        narrative.setCohortId(ctx.comparison().getCohortId());
        narrative.setOrgId(ctx.comparison().getOrgId());
        narrative.setUserId(ctx.comparison().getUserId());
        narrative.setBaselinePillarId(candidate.pillar().getBaselinePillarId());
        narrative.setDistancePillarId(candidate.distancePillarId());
        narrative.setPillarNameSnapshot(candidate.pillar().getPillarNameSnapshot());
        narrative.setKind(NarrativeKind.parse(result.kind()).orElseThrow());
        narrative.setBody(result.narrative().trim());
        narrative.setClosingAction(result.closingAction().isBlank() ? null : result.closingAction().trim());
        narrative.setBandKey(bandKey);
        narrative.setDecline(decline);
        narrative.setGeneratedAt(Instant.now());
        narrative.setAiModelUsed(provenance.model());
        narrative.setAiTemperature(provenance.temperature());
        narrative.setAiPromptVersionId(provenance.systemPromptVersionId());
        narrative.setConfigSnapshot(new ShiftNarrative.Snapshot(
                ctx.wording(), bandKey, candidate.pillar().getBandLabel()));
        if (ctx.wording().autoApprove()) {
            // §6 auto-approve: stamped by the SYSTEM — approvedBy stays null so
            // the UI never attributes a machine decision to a person.
            narrative.setStatus(NarrativeStatus.APPROVED);
            narrative.setApprovedAt(Instant.now());
        }
        return Optional.of(narratives.save(narrative));
    }

    /**
     * The user message: pillar name + the four text blocks, and nothing else.
     * Deliberately built from {@link Candidate}, which carries no score.
     */
    static String userMessage(Candidate candidate) {
        StringBuilder sb = new StringBuilder();
        sb.append("PILLAR: ").append(candidate.pillar().getPillarNameSnapshot()).append("\n\n");
        appendBlock(sb, "BEFORE — what's working", candidate.beforeWorking());
        appendBlock(sb, "BEFORE — what can improve", candidate.beforeImprove());
        appendBlock(sb, "AFTER — what's working", candidate.afterWorking());
        appendBlock(sb, "AFTER — what can improve", candidate.afterImprove());
        return sb.toString();
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
            (candidate.hasEnoughBeforeText() ? available : insufficient).add(option);
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
        if (request.kind() != null && !request.kind().isBlank()) {
            narrative.setKind(NarrativeKind.parse(request.kind())
                    .orElseThrow(() -> new BadRequestException("Unknown narrative kind: " + request.kind())));
        }
        narrative.setBody(request.body().trim());
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

    /**
     * The other half of the recompute seam: re-derive each narrative's decline
     * flag and band label from the freshly rebuilt pillar rows, so an edited
     * band config actually binds the guardrail and the chips. Must run AFTER the
     * rebuild.
     */
    public int restampBandsForCohort(UUID cohortId) {
        return narratives.restampBandsForCohort(cohortId);
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
     * pair, exactly as the growth read resolves it.
     */
    private UUID anchorCohort(UUID userId) {
        return reads.memberPairCohort(userId)
                .map(ComparisonReadRepository.PairCohortRow::cohortId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Designated comparison pair for member", userId.toString()));
    }

    /** Everything one founder's narrative work needs, read once. */
    record Context(FounderComparison comparison, NarrativeWording wording,
                   List<Candidate> candidates, Set<UUID> existingPillarIds) {
    }

    /** A mapped pillar with both sides' text in hand — a narrative candidate. */
    record Candidate(FounderComparisonPillar pillar, List<String> beforeWorking,
                     List<String> beforeImprove, List<String> afterWorking, List<String> afterImprove) {

        UUID distancePillarId() {
            return pillar.getDistancePillarId();
        }

        boolean hasEnoughBeforeText() {
            return NarrativeGuardrails.hasEnoughBeforeText(beforeWorking, beforeImprove);
        }
    }

    private Context context(UUID cohortId, UUID userId) {
        FounderComparison comparison = comparisons.findByCohortIdAndUserId(cohortId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Comparison for user", userId.toString()));

        Map<UUID, RawPillarText> before = reads.pillarTextBlocks(comparison.getBaselineSubmissionId());
        Map<UUID, RawPillarText> after = reads.pillarTextBlocks(comparison.getDistanceSubmissionId());

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
                        lines(after.get(p.getDistancePillarId()), false)))
                .toList();

        Set<UUID> existing = new HashSet<>(narratives.findByCohortIdAndUserId(cohortId, userId).stream()
                .map(ShiftNarrative::getDistancePillarId).toList());
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

    /** jsonb string array → list; an absent row or unreadable value reads as "no text". */
    private static List<String> lines(RawPillarText row, boolean working) {
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

    private static ShiftNarrativeDto toDto(ShiftNarrative n, Map<UUID, String> actorNames,
                                           boolean detached) {
        return new ShiftNarrativeDto(n.getId(), n.getBaselinePillarId(), n.getDistancePillarId(),
                n.getPillarNameSnapshot(), n.getKind().name(), n.getBody(), n.getClosingAction(),
                n.isDecline(), detached,
                n.getBandKey(), n.getStatus().name(), n.getGeneratedAt(),
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
