package com.bvisionry.organization.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Write shape of {@code PUT /api/organizations/{id}/branding} — the org's
 * white-label logo and brand colour (policy {@code decisions.white_label}:
 * logo + colours only; custom domains and branded senders are closed as out).
 *
 * <p>Both fields are nullable and null means CLEARED: sending
 * {@code {"brandColor": null, "logoMarker": null}} returns the org to the stock
 * Bvisionry theme. There is no separate delete endpoint because there is no
 * separate resource — branding is two columns on the org row.
 *
 * <p>Deliberately NOT the same record as the response: the response also
 * carries a server-derived presigned {@code logoUrl}, and a shared record would
 * either invite a client to send it (ignored, silently) or force the response
 * to omit it.
 *
 * @param brandColor  lower- or upper-case {@code #rrggbb}, or null to clear.
 *                    ONE colour: every {@code *-foreground} token is derived
 *                    from it by WCAG relative luminance, so the admin never
 *                    picks a text colour and an unreadable pairing cannot be
 *                    expressed. The pattern is also the first half of the
 *                    CSS-injection defence — the value ends up inside an SSR
 *                    {@code <style>} block.
 * @param logoMarker  a {@code minio://} marker obtained from
 *                    {@code POST /api/v1/media?orgId=<this org>&kind=image}, or
 *                    null to clear. Validated against THIS org's key prefix
 *                    before it is stored; see
 *                    {@code OrganizationBrandingService}.
 */
public record UpdateBrandingRequest(
        @Pattern(regexp = "^#[0-9a-fA-F]{6}$",
                 message = "Brand colour must be a #rrggbb hex value")
        String brandColor,

        @Size(max = 512)
        String logoMarker) {
}
