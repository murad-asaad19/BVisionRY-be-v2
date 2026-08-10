package com.bvisionry.platform.dto;

import com.bvisionry.common.scoringconfig.ScoringBands;

import java.time.Instant;
import java.util.List;

/**
 * The whole "Scoring &amp; Labels" console page in one GET (spec §7). Each
 * section carries its own {@code updatedAt}/{@code updatedBy} (the editor's
 * email) so every card can show "Last saved …" — null when a section still
 * rides on shipped defaults.
 */
public record ScoringConfigResponse(
        ParticipationFormulaSection participationFormula,
        BandsSection participationBands,
        BandsSection shiftBands,
        QualityTagsSection qualityTags,
        NarrativeWordingSection narrativeWording) {

    /**
     * One weighted participation category. {@code key} is stable identity
     * ({@code assignments}, {@code workshops}, …); {@code label} is
     * presentation. {@code computed} = derived from the task spine rather than
     * entered by hand; the {@code assignments} category is protected — always
     * present, always computed.
     */
    public record ParticipationCategory(String key, String label, int weight, boolean computed) {
    }

    public record ParticipationFormulaSection(
            List<ParticipationCategory> categories, Instant updatedAt, String updatedBy) {
    }

    public record BandsSection(List<ScoringBands.Band> bands, Instant updatedAt, String updatedBy) {
    }

    public record QualityTag(String key, String label) {
    }

    public record QualityTagsSection(List<QualityTag> tags, Instant updatedAt, String updatedBy) {
    }

    /**
     * The fixed sentences the system emits (spec §7): the "not enough
     * before-data" line and the decline-close instruction the shift-narrative
     * job's guardrail validates against, plus the §6 auto-approve toggle
     * (OFF by default = human approval required before a narrative reaches the
     * founder).
     */
    public record NarrativeWordingSection(
            String notEnoughDataSentence, String declineCloseInstruction,
            boolean autoApprove,
            Instant updatedAt, String updatedBy) {
    }
}
