package com.bvisionry.workshops.dto;

import java.util.List;
import java.util.UUID;

/**
 * One member's final-review answers, read by an admin — the same recap the
 * member sees on their done screen: per-QUESTION answers (card + response)
 * plus the team's shared sort piles.
 */
public record MemberAnswersResponse(
        UUID workshopId,
        String workshopName,
        UUID userId,
        String userName,
        boolean lead,
        UUID teamId,
        String teamName,
        List<PlayResponse.RecapRow> recap,
        List<PlayResponse.SortRecap> sortRecaps) {
}
