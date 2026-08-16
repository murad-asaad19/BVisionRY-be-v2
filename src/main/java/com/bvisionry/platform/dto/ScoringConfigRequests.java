package com.bvisionry.platform.dto;

import com.bvisionry.common.scoringconfig.ScoringBands;
import com.bvisionry.platform.dto.ScoringConfigResponse.ParticipationCategory;
import com.bvisionry.platform.dto.ScoringConfigResponse.QualityTag;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * PUT bodies for the per-section Scoring &amp; Labels endpoints. Structural
 * rules (weights sum to 100, band contiguity, protected Assignments category)
 * are service-layer checks surfaced as {@code fieldErrors} — bean validation
 * here only guards nullability.
 */
public final class ScoringConfigRequests {

    private ScoringConfigRequests() {
    }

    public record UpdateParticipationFormulaRequest(
            @NotNull List<ParticipationCategory> categories) {
    }

    public record UpdateBandsRequest(@NotNull List<ScoringBands.Band> bands) {
    }

    public record UpdateQualityTagsRequest(@NotNull List<QualityTag> tags) {
    }

    /** {@code autoApprove} is nullable so an older client's body still saves (null = off). */
    public record UpdateNarrativeWordingRequest(
            @NotNull String notEnoughDataSentence,
            @NotNull String declineCloseInstruction,
            Boolean autoApprove) {
    }
}
