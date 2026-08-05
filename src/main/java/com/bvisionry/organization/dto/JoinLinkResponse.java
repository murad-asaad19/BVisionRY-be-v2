package com.bvisionry.organization.dto;

import com.bvisionry.organization.entity.JoinLink;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code token} IS the redeemable secret — {@code POST /api/join/{token}} is
 * {@code permitAll()} and CSRF-exempt and provisions a permanent tenant account — and
 * it is emitted deliberately. Do not "fix" it by nulling it out the way
 * {@code InvitationResponse#withoutToken} does; that precedent does not transfer.
 *
 * <p>{@code InvitationResponse} splits because ONE DTO serves two audiences: an admin
 * LISTING would otherwise hand out other principals' secrets, including a SUPER_ADMIN's
 * invite of a new ORG_ADMIN, letting the lister complete an account it was never offered.
 * This record has no listing. Both emitters — {@code POST} (generate) and {@code GET}
 * (reveal) on {@code /api/organizations/{orgId}/join-link} — are {@code SUPER_ADMIN} or
 * {@code ORG_ADMIN}-in-org, i.e. the caller who just created the secret or owns it, which
 * is precisely the {@code withToken} side of that split. Nothing is escalated: the same
 * caller can mint a replacement with one POST, and the link only ever provisions a MEMBER
 * of the org it already administers. Withholding it would break the feature outright —
 * the admin reads the link in order to share it ({@code #join-link-url} in the web app).
 *
 * <p>The real exposure was a weaker credential reaching the reveal:
 * {@code DownloadTokenAuthenticationFilter} used to authenticate any GET, so a 60-second
 * URL credential could read this and be traded for a permanent account. That is closed at
 * the filter — which covers every sibling secret-returning GET, not just this one — and
 * pinned by {@code DownloadTokenAuthenticationFilterTest#nonExportPath_doesNotAuthenticate}.
 *
 * <p>The public pre-redemption fetch ({@code GET /api/join/{token}}, {@code permitAll})
 * returns {@link JoinLinkInfoResponse}, which carries no token. Keep it that way.
 */
public record JoinLinkResponse(
        UUID id, UUID token, UUID organizationId, UUID workshopId, boolean isActive,
        Instant expiresAt, Instant createdAt
) {
    public static JoinLinkResponse from(JoinLink link) {
        return new JoinLinkResponse(link.getId(), link.getToken(),
                link.getOrganization().getId(), link.getWorkshopId(), link.isActive(),
                link.getExpiresAt(), link.getCreatedAt());
    }
}
