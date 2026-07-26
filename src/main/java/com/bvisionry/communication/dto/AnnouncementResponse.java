package com.bvisionry.communication.dto;

import java.time.Instant;
import java.util.UUID;

import com.bvisionry.communication.repository.AnnouncementReadRepository.FeedRow;

/**
 * One post on an author's cohort feed. {@code body} is the stored plain text —
 * URLs are auto-linked by the client at render time, never stored as markup.
 *
 * @param authorName null once the author's account is erased
 * @param flagged    a member reported this post (see {@code flagged_at}) — a
 *                   MODERATOR-ONLY signal, always false for a non-moderator
 *                   reader, see {@link #from(FeedRow, boolean)}
 */
public record AnnouncementResponse(UUID id, UUID cohortId, String cohortName, String authorName,
                                   String body, boolean flagged, Instant createdAt) {

    /**
     * @param moderator whether THIS reader may see that a post was reported. A
     *                  coach is not one: they are an authorized reader of their
     *                  own cohort's feed, and "a member reported you" with no
     *                  context, no recourse and continuing authority over that
     *                  member near-identifies the reporter in a small cohort.
     *                  Suppressed HERE rather than in the client, because the
     *                  client is not a place a secret can be kept.
     */
    public static AnnouncementResponse from(FeedRow row, boolean moderator) {
        return new AnnouncementResponse(row.id(), row.cohortId(), row.cohortName(),
                row.authorName(), row.body(), moderator && row.flagged(), row.createdAt());
    }
}
