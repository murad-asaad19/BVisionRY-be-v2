package com.bvisionry.communication.dto;

import java.time.Instant;
import java.util.UUID;

import com.bvisionry.communication.repository.AnnouncementReadRepository.MemberFeedRow;

/**
 * One announcement as its RECIPIENT sees it ({@code GET /api/my/announcements}
 * — redesign spec §2.1's coach rail + §2.2). Carries the author's name and raw
 * role (the client renders the label) and the §7b timestamp. Deliberately no
 * {@code flagged}: moderation state is an org-admin signal, never a
 * recipient's.
 *
 * @param authorName null once the author's account is erased
 * @param authorRole e.g. {@code COACH} / {@code ORG_ADMIN}; null with the author
 */
public record MyAnnouncementResponse(UUID id, String cohortName, String authorName,
                                     String authorRole, String body, Instant createdAt) {

    public static MyAnnouncementResponse from(MemberFeedRow row) {
        return new MyAnnouncementResponse(row.id(), row.cohortName(), row.authorName(),
                row.authorRole(), row.body(), row.createdAt());
    }
}
