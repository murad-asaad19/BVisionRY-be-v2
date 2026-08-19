package com.bvisionry.comparison.web;

import com.bvisionry.aicalllog.dto.CallMetadata;
import com.bvisionry.aiconfig.service.OpenRouterChatService;
import com.bvisionry.aiconfig.service.OpenRouterChatService.AIResponse;
import com.bvisionry.common.audit.AuditLogger;
import com.bvisionry.common.dto.MemberGrowthSummaryResult;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.comparison.domain.FounderComparison;
import com.bvisionry.comparison.domain.MemberGrowthSummary;
import com.bvisionry.comparison.domain.NarrativeKind;
import com.bvisionry.comparison.domain.NarrativeStatus;
import com.bvisionry.comparison.domain.ShiftNarrative;
import com.bvisionry.comparison.dto.MemberGrowthSummaryDto;
import com.bvisionry.comparison.dto.NarrativeRequests.UpdateGrowthSummaryRequest;
import com.bvisionry.comparison.dto.ShiftNarrativeDto;
import com.bvisionry.comparison.repository.ComparisonReadRepository;
import com.bvisionry.comparison.repository.MemberGrowthSummaryRepository;
import com.bvisionry.comparison.repository.ShiftNarrativeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The member-level growth summary (redesign spec §3): ONE qualitative paragraph
 * per founder per cohort, synthesised from the per-pillar narratives a human has
 * already approved.
 *
 * <h2>The gate before the gate</h2>
 * Generation is refused until EVERY pillar that could carry a narrative has an
 * APPROVED one, and the refusal names the pillars still outstanding. The
 * eligible set is {@link ShiftNarrativeService}'s own candidate notion, reused
 * rather than re-derived, so "what the summary is waiting for" and "what the
 * narrative screen offers to generate" can never disagree.
 *
 * <h2>Review gate</h2>
 * Identical to the narratives': DRAFT → APPROVED, member sees APPROVED only, an
 * edit returns it to DRAFT, and a recompute returns it to DRAFT (the numbers
 * moved under prose nobody has re-read). Regenerating overwrites the single row
 * in place — there is one summary, not a pile of drafts.
 *
 * <p>ponytail: re-editing an approved NARRATIVE does not invalidate an approved
 * summary. Recompute covers the case that matters (§7) and the staff button
 * regenerates on demand; watching every narrative write to chase the summary
 * would be machinery for a case a click already handles.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemberGrowthSummaryService {

    private final MemberGrowthSummaryRepository summaries;
    private final ShiftNarrativeRepository narrativeRows;
    private final ShiftNarrativeService narratives;
    private final ComparisonReadRepository reads;
    private final OpenRouterChatService chat;
    private final AuditLogger auditLogger;

    /* ======================================================== generation */

    /**
     * The staff "Generate summary" button. Deliberately NOT {@code @Transactional}:
     * it makes a model call, and a database connection must not sit open across a
     * provider round-trip.
     */
    public MemberGrowthSummaryDto generate(UUID userId, UUID actorId) {
        UUID cohortId = narratives.anchorCohort(userId);
        ShiftNarrativeService.Context ctx = narratives.context(cohortId, userId);
        Map<UUID, ShiftNarrative> approved = narrativeRows
                .findByCohortIdAndUserIdAndStatus(cohortId, userId, NarrativeStatus.APPROVED).stream()
                .collect(Collectors.toMap(ShiftNarrative::getDistancePillarId, Function.identity(),
                        (a, b) -> a));

        // §3: every pillar that COULD carry a narrative must carry an approved
        // one. Pillars under either text floor are not eligible and never block —
        // they have no narrative to wait for. Same predicate the generator uses,
        // deliberately: a pillar this gate waits for but the generator refuses
        // would block the summary forever.
        List<ShiftNarrativeService.Candidate> eligible = ctx.candidates().stream()
                .filter(ShiftNarrativeService.Candidate::hasEnoughMaterial)
                .toList();
        List<String> missing = eligible.stream()
                .filter(c -> !approved.containsKey(c.distancePillarId()))
                .map(c -> c.pillar().getPillarNameSnapshot())
                .toList();
        if (!missing.isEmpty()) {
            throw new BadRequestException(
                    "Every pillar narrative must be approved before the growth summary can be "
                            + "generated. Still waiting on: " + String.join(", ", missing) + ".");
        }
        if (eligible.isEmpty()) {
            throw new BadRequestException(
                    "There are no approved pillar narratives to summarise yet.");
        }

        AIResponse<MemberGrowthSummaryResult> response = chat.generateMemberGrowthSummary(
                userMessage(ctx.comparison(), eligible, approved),
                new CallMetadata(ctx.comparison().getDistanceSubmissionId(), null, null));
        MemberGrowthSummaryResult result = response.parsed();
        // Whichever shape arrived is the shape stored. An installation whose
        // customised template still asks for prose keeps producing prose (V193
        // deliberately leaves its wording alone), and a breakdown is never
        // faked out of a paragraph nobody wrote that way.
        List<ShiftNarrative.Item> items = result == null ? List.of() : parseItems(result);
        String body = result == null ? "" : result.summary().trim();
        // The "did a usable summary come back" check, asserted on what would
        // actually be PERSISTED rather than on the raw response: a breakdown
        // whose kinds all fail NarrativeKind.parse reconciles to nothing, and
        // saving that would overwrite the one row — possibly an approved,
        // human-reviewed one — with an empty draft. Refuse before the load.
        if (items.isEmpty() && body.isBlank()) {
            throw new BadRequestException(
                    "The AI did not return a growth summary. Nothing was saved — try again in a moment.");
        }

        OpenRouterChatService.Provenance provenance = response.provenance();
        MemberGrowthSummary summary = summaries.findByCohortIdAndUserId(cohortId, userId)
                .orElseGet(MemberGrowthSummary::new);
        summary.setCohortId(cohortId);
        summary.setOrgId(ctx.comparison().getOrgId());
        summary.setUserId(userId);
        summary.setItems(items.isEmpty() ? null : items);
        summary.setBody(items.isEmpty() ? body : null);
        // Regeneration is a fresh draft: the old approval and the old reviewer's
        // edit stamp described text that no longer exists.
        summary.setStatus(NarrativeStatus.DRAFT);
        summary.setApprovedBy(null);
        summary.setApprovedAt(null);
        summary.setEditedBy(null);
        summary.setEditedAt(null);
        if (ctx.wording().autoApprove()) {
            // The SAME platform toggle the per-pillar narratives obey
            // (ShiftNarrativeService): an installation that trusts the model
            // enough to publish its narratives unreviewed must not still be
            // asked to hand-approve the summary written FROM those narratives.
            // Stamped by the system — approvedBy stays null so the UI never
            // attributes a machine decision to a person.
            summary.setStatus(NarrativeStatus.APPROVED);
            summary.setApprovedAt(Instant.now());
        }
        summary.setGeneratedAt(Instant.now());
        summary.setAiModelUsed(provenance.model());
        summary.setAiTemperature(provenance.temperature());
        summary.setAiPromptVersionId(provenance.systemPromptVersionId());
        MemberGrowthSummary saved = summaries.save(summary);
        auditLogger.log(actorId, saved.getOrgId(), "MEMBER_GROWTH_SUMMARY_GENERATED",
                "MemberGrowthSummary", saved.getId(), Map.of("pillars", eligible.size()));
        return toDto(saved, names(saved));
    }

    /**
     * The model's breakdown, kinds validated here rather than trusted. An
     * unrecognised kind drops its observation instead of failing the whole
     * generation — the rest of the breakdown is still usable prose a reviewer
     * can read. When NOTHING survives, the caller refuses the whole generation
     * (it checks this list, not the raw response) rather than persisting an
     * empty summary over the existing row.
     */
    private static List<ShiftNarrative.Item> parseItems(MemberGrowthSummaryResult result) {
        return result.items().stream()
                .filter(item -> !item.text().isBlank())
                .flatMap(item -> NarrativeKind.parse(item.kind()).stream()
                        .map(kind -> new ShiftNarrative.Item(kind, item.text().trim())))
                .toList();
    }

    /**
     * The model's ONLY input: the approved breakdowns, pillar by pillar, in the
     * comparison's own pillar order. Built from the candidate list, so a pillar
     * the comparison no longer measures cannot smuggle a detached narrative in.
     */
    private static String userMessage(FounderComparison comparison,
                                      List<ShiftNarrativeService.Candidate> eligible,
                                      Map<UUID, ShiftNarrative> approved) {
        StringBuilder sb = new StringBuilder("APPROVED PILLAR NARRATIVES:\n");
        // Scores as CONTEXT (operator decision 2026-08-19, same as the pillar
        // prompt): without them the model weighs a +2 pillar and a +40 pillar
        // identically. The no-numbers OUTPUT rule is restated inline so it
        // binds even on installations with a customised system prompt.
        sb.append("(each pillar's before → after scores, and the OVERALL line, are context "
                + "for weighing what mattered most ONLY — never repeat or state any number "
                + "in the output)\n\n");
        if (comparison.getOverallBefore() != null && comparison.getOverallAfter() != null) {
            sb.append("OVERALL: before ").append(ShiftNarrativeService.pct(comparison.getOverallBefore()))
                    .append("% → after ").append(ShiftNarrativeService.pct(comparison.getOverallAfter())).append("%\n\n");
        }
        for (ShiftNarrativeService.Candidate candidate : eligible) {
            ShiftNarrative narrative = approved.get(candidate.distancePillarId());
            sb.append("PILLAR: ").append(narrative.getPillarNameSnapshot());
            if (candidate.pillar().getBeforePct() != null
                    && candidate.pillar().getAfterPct() != null) {
                sb.append(" — before ").append(ShiftNarrativeService.pct(candidate.pillar().getBeforePct()))
                        .append("% → after ").append(ShiftNarrativeService.pct(candidate.pillar().getAfterPct()))
                        .append('%');
            }
            sb.append('\n');
            if (narrative.getItems() == null || narrative.getItems().isEmpty()) {
                // A pre-V189 row: its single paragraph IS the whole breakdown.
                sb.append("- ").append(narrative.getKind()).append(": ")
                        .append(narrative.getBody()).append('\n');
            } else {
                narrative.getItems().forEach(item -> sb.append("- ").append(item.kind().name())
                        .append(": ").append(item.text()).append('\n'));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /* ====================================================== the read side */

    /** The staff read: draft or approved, whichever exists. */
    @Transactional(readOnly = true)
    public Optional<MemberGrowthSummaryDto> forReview(UUID userId) {
        return summaries.findByCohortIdAndUserId(narratives.anchorCohort(userId), userId)
                .map(s -> toDto(s, names(s)));
    }

    /**
     * The member's door: the APPROVED body or nothing. The one filter every
     * member-facing surface (My Growth, PDF, Excel) reads through, so a draft
     * cannot leak past it.
     */
    @Transactional(readOnly = true)
    public String approvedBodyFor(UUID cohortId, UUID userId) {
        return approved(cohortId, userId).map(MemberGrowthSummary::getBody).orElse(null);
    }

    /** The breakdown half of {@link #approvedBodyFor} — null unless approved. */
    @Transactional(readOnly = true)
    public List<ShiftNarrativeDto.NarrativeItemDto> approvedItemsFor(UUID cohortId, UUID userId) {
        return approved(cohortId, userId).map(MemberGrowthSummaryService::itemDtos).orElse(null);
    }

    private Optional<MemberGrowthSummary> approved(UUID cohortId, UUID userId) {
        return summaries.findByCohortIdAndUserId(cohortId, userId)
                .filter(s -> s.getStatus() == NarrativeStatus.APPROVED);
    }

    /* ==================================================== the review gate */

    /** Idempotent, like the narrative gate: re-approving keeps the ORIGINAL stamp. */
    @Transactional
    public MemberGrowthSummaryDto approve(UUID userId, UUID actorId) {
        MemberGrowthSummary summary = requireOwned(userId);
        if (summary.getStatus() == NarrativeStatus.APPROVED) {
            return toDto(summary, names(summary));
        }
        summary.setStatus(NarrativeStatus.APPROVED);
        summary.setApprovedBy(actorId);
        summary.setApprovedAt(Instant.now());
        auditLogger.log(actorId, summary.getOrgId(), "MEMBER_GROWTH_SUMMARY_APPROVED",
                "MemberGrowthSummary", summary.getId(), Map.of());
        MemberGrowthSummary saved = summaries.save(summary);
        return toDto(saved, names(saved));
    }

    /** An edit returns an approved summary to DRAFT — changed prose has to be signed off again. */
    @Transactional
    public MemberGrowthSummaryDto update(UUID userId, UpdateGrowthSummaryRequest request, UUID actorId) {
        MemberGrowthSummary summary = requireOwned(userId);
        List<ShiftNarrative.Item> items = request.items().stream()
                .map(item -> new ShiftNarrative.Item(
                        NarrativeKind.parse(item.kind()).orElseThrow(() -> new BadRequestException(
                                "Unknown observation kind: " + item.kind())),
                        item.text().trim()))
                .toList();
        summary.setItems(items);
        // A legacy paragraph edited through here migrates to items — one row
        // must never carry both shapes.
        summary.setBody(null);
        summary.setEditedBy(actorId);
        summary.setEditedAt(Instant.now());
        summary.setStatus(NarrativeStatus.DRAFT);
        summary.setApprovedBy(null);
        summary.setApprovedAt(null);
        auditLogger.log(actorId, summary.getOrgId(), "MEMBER_GROWTH_SUMMARY_EDITED",
                "MemberGrowthSummary", summary.getId(), Map.of());
        MemberGrowthSummary saved = summaries.save(summary);
        return toDto(saved, names(saved));
    }

    /** Discard a draft. Approved summaries are edited (back to draft) first, as narratives are. */
    @Transactional
    public void delete(UUID userId, UUID actorId) {
        MemberGrowthSummary summary = requireOwned(userId);
        if (summary.getStatus() == NarrativeStatus.APPROVED) {
            throw new BadRequestException(
                    "An approved growth summary cannot be deleted. Edit it first — that returns it to draft.");
        }
        summaries.delete(summary);
        auditLogger.log(actorId, summary.getOrgId(), "MEMBER_GROWTH_SUMMARY_DELETED",
                "MemberGrowthSummary", summary.getId(), Map.of());
    }

    /** §7's recompute rule, the summary's half — called from the same seam as the narratives'. */
    public int revertApprovalsForCohort(UUID cohortId) {
        int reverted = summaries.revertApprovalsForCohort(cohortId);
        if (reverted > 0) {
            log.info("Recompute returned {} approved growth summary/summaries to draft for cohort {}",
                    reverted, cohortId);
        }
        return reverted;
    }

    /** Org-scoped {@link #revertApprovalsForCohort}: only the given members' summaries. */
    public int revertApprovalsForCohortMembers(UUID cohortId, java.util.Collection<UUID> userIds) {
        return userIds.isEmpty() ? 0 : summaries.revertApprovalsForCohortMembers(cohortId, userIds);
    }

    /* ---------------------------------------------------------- plumbing */

    /**
     * Tenancy guard: the summary is looked up BY the founder the caller was
     * already authorized for, so there is no id from another founder to
     * mis-resolve in the first place.
     */
    private MemberGrowthSummary requireOwned(UUID userId) {
        return summaries.findByCohortIdAndUserId(narratives.anchorCohort(userId), userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Growth summary for member", userId.toString()));
    }

    /** Reviewer names for the §7b stamps; erased reviewers simply drop out of the map. */
    private Map<UUID, String> names(MemberGrowthSummary summary) {
        Set<UUID> ids = new HashSet<>();
        if (summary.getApprovedBy() != null) ids.add(summary.getApprovedBy());
        if (summary.getEditedBy() != null) ids.add(summary.getEditedBy());
        return ids.isEmpty() ? Map.of() : reads.userNames(ids);
    }

    private static MemberGrowthSummaryDto toDto(MemberGrowthSummary s, Map<UUID, String> actorNames) {
        return new MemberGrowthSummaryDto(s.getId(), itemDtos(s), s.getBody(), s.getStatus().name(),
                s.getGeneratedAt(),
                s.getApprovedBy(), nameOf(actorNames, s.getApprovedBy()), s.getApprovedAt(),
                s.getEditedBy(), nameOf(actorNames, s.getEditedBy()), s.getEditedAt());
    }

    /** Null (not an empty list) on a legacy row, so a reader can tell the shapes apart. */
    private static List<ShiftNarrativeDto.NarrativeItemDto> itemDtos(MemberGrowthSummary s) {
        return s.getItems() == null ? null : s.getItems().stream()
                .map(i -> new ShiftNarrativeDto.NarrativeItemDto(i.kind().name(), i.text()))
                .toList();
    }

    /** {@code Map.of()} throws on a null key, and a never-approved summary has one. */
    private static String nameOf(Map<UUID, String> actorNames, UUID actorId) {
        return actorId == null ? null : actorNames.get(actorId);
    }
}
