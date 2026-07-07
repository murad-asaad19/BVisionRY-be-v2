package com.bvisionry.common.event;

import java.util.List;
import java.util.UUID;

/**
 * Workshop domain events. They live in {@code common} so the publishing and
 * consuming slices don't have to import each other (the architecture rules
 * forbid new cross-feature dependencies) — same pattern as
 * {@link ProgramFlowEvents}.
 */
public final class WorkshopEvents {

    private WorkshopEvents() {
    }

    /**
     * A member joined the org through a workshop-bound join link. Published by
     * the {@code organization} slice's join-link accept paths; consumed by the
     * {@code workshops} slice, which assigns the member to the least-filled
     * team (join order → sequential fill → equal team sizes).
     */
    public record JoinedViaLink(UUID workshopId, UUID userId) {
    }

    /**
     * A team lead completed their last task of an exercise, sharing the
     * results — the moment the team's member tasks unlock. Published by the
     * {@code workshops} slice; consumed by the {@code notification} slice's
     * push handler to nudge the non-lead members.
     */
    public record ResultsShared(UUID workshopId, String workshopName,
                                String exerciseTitle, List<UUID> memberIds) {
    }
}
