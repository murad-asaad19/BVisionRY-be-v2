package com.bvisionry.organization.dto;

/**
 * Read shape of {@code GET /api/organizations/{id}/branding}.
 *
 * <p>Served from its own endpoint rather than folded into
 * {@code GET /api/v1/auth/me} on purpose, and the ground is REVERSAL
 * ASYMMETRY: a field added to the hot, contract-pinned auth DTO gets depended
 * on everywhere and can never be cleanly removed, whereas a separate endpoint
 * can be dropped or reshaped at will. The secondary benefit is that this
 * response can be cached independently while an auth response never can.
 *
 * <p>What is NOT a benefit, stated plainly so nobody re-derives it: the two
 * fetches are strictly SEQUENTIAL, not parallel. The org id this endpoint needs
 * comes FROM {@code /me}, so the app layout cannot fire them together — see the
 * comment in {@code web/src/app/(app)/layout.tsx}, which says so. The cost is
 * one extra cookie-forwarded no-store request per render; it is accepted, not
 * argued away.
 *
 * @param brandColor the org's {@code #rrggbb} brand colour, or null when the
 *                   org has no branding configured (render the stock theme)
 * @param logoMarker the persisted {@code minio://} marker, or null. Exposed so
 *                   the admin surface can tell "no logo" from "logo whose
 *                   presigned URL just expired" without a second call.
 * @param logoUrl    a freshly presigned GET URL for {@code logoMarker}, or
 *                   null. Short-lived (bvisionry.minio.presigned-expiry-minutes,
 *                   60 by default) — never cache it past that, and never
 *                   persist it.
 */
public record BrandingResponse(String brandColor, String logoMarker, String logoUrl) {
}
