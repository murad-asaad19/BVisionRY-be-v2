package com.bvisionry.comparison.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** Write bodies for the narrative review gate (spec §6). */
public final class NarrativeRequests {

    private NarrativeRequests() {
    }

    /**
     * On-demand generation for one mapped pillar ("Generate for another pillar…").
     *
     * <p>{@code regenerate = true} replaces the pillar's existing narrative in
     * place — model call first, then overwrite, so a failed generation leaves
     * the old prose untouched. The row returns to DRAFT whatever its status
     * was; new prose has never been signed off (same rule as an edit).
     */
    public record GenerateNarrativeRequest(@NotNull UUID distancePillarId, Boolean regenerate) {

        public boolean isRegenerate() {
            return Boolean.TRUE.equals(regenerate);
        }
    }

    /**
     * A reviewer's edit — the whole breakdown, replaced (spec §2). A legacy
     * single-paragraph row edited through here migrates to items: the edit IS
     * the new shape, so keeping the old pair beside it would leave two sources
     * of truth on one row.
     */
    public record UpdateNarrativeRequest(@NotEmpty @Valid List<NarrativeItem> items,
                                         String closingAction) {

        /** One observation: one {@code NarrativeKind} + its prose. */
        public record NarrativeItem(@NotBlank String kind, @NotBlank String text) {
        }
    }

    /**
     * A reviewer's edit of the member-level growth summary (spec §3) — the
     * whole breakdown, replaced, exactly like {@link UpdateNarrativeRequest}.
     * A pre-V193 paragraph edited through here migrates to items: the edit IS
     * the new shape, so keeping the old body beside it would leave two sources
     * of truth on one row.
     */
    public record UpdateGrowthSummaryRequest(@NotEmpty @Valid List<NarrativeItem> items) {

        /** One observation: one {@code NarrativeKind} + its prose. */
        public record NarrativeItem(@NotBlank String kind, @NotBlank String text) {
        }
    }

    /**
     * The cohort-level generate button (spec §4). One flag: send REAL member
     * names to the provider instead of "Member N". Deliberately open to any org
     * admin — the operator ruling that made §4 looser than the SUPER_ADMIN-only
     * export precedent.
     */
    public record GenerateCohortSummaryRequest(boolean includeNames) {
    }
}
