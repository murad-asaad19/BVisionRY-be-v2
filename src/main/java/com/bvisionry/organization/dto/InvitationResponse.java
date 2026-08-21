package com.bvisionry.organization.dto;

import com.bvisionry.common.enums.UserRole;
import com.bvisionry.organization.entity.Invitation;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * One DTO, two audiences — and only one of them may see {@code token}.
 *
 * <p>{@code token} is the REDEEMABLE SECRET: {@code POST /api/invitations/{token}/accept}
 * is {@code permitAll()} and CSRF-exempt, and it mints a session on an account whose
 * password the caller chooses. Anyone holding the token can therefore complete the
 * invited account, whoever it was invited by. Handing that to a listing endpoint let an
 * ORG_ADMIN finish an account created by someone else's invite — including a
 * SUPER_ADMIN's invite of a new ORG_ADMIN into that org — and keep its credentials.
 *
 * <p>So the token is emitted by the two paths whose caller already holds or just
 * created it ({@link #withToken}) and withheld by the org-scoped listing
 * ({@link #withoutToken}). The invitee still receives it in the invitation EMAIL;
 * redemption is unchanged.
 */
public record InvitationResponse(
        UUID id, String email, String organizationName, UserRole role, String status,

        @Schema(nullable = true, description = """
                The redeemable invitation secret. Present when the caller created the \
                invitation (POST /members/invite) or already holds the token \
                (GET /api/invitations/{token}). ALWAYS null in the org-scoped listing \
                (GET /api/organizations/{orgId}/invitations) — the token is delivered \
                to the invitee by email, never to an administrator's screen.""")
        UUID token,

        Instant expiresAt, Instant acceptedAt, Instant createdAt,
        Instant firstViewedAt, Instant lastViewedAt, int viewCount,
        long attemptCount, long failedAttemptCount, Instant lastAttemptAt
) {
    /**
     * The invitation as seen by a caller that already holds its token: the inviter,
     * from the {@code POST /members/invite} result, and the invitee, from the public
     * {@code GET /api/invitations/{token}} fetch. Attempt counters default to zero —
     * neither audience has the summary handy, and neither renders them.
     */
    public static InvitationResponse withToken(Invitation inv) {
        return build(inv, inv.getToken(), InvitationAttemptSummary.empty(inv.getId()));
    }

    /**
     * The invitation as seen by an ADMINISTRATOR listing an organization's invites:
     * acceptance telemetry included, redeemable token withheld. Never widen this to
     * emit the token — see the type javadoc for what that costs.
     */
    public static InvitationResponse withoutToken(Invitation inv, InvitationAttemptSummary summary) {
        return build(inv, null, summary);
    }

    private static InvitationResponse build(Invitation inv, UUID token, InvitationAttemptSummary summary) {
        return new InvitationResponse(
                inv.getId(), inv.getEmail(), inv.getOrganization().getName(), inv.getRole(), inv.getStatus().name(),
                token, inv.getExpiresAt(), inv.getAcceptedAt(), inv.getCreatedAt(),
                inv.getFirstViewedAt(), inv.getLastViewedAt(), inv.getViewCount(),
                summary.attemptCount(), summary.failedAttemptCount(), summary.lastAttemptAt());
    }
}
