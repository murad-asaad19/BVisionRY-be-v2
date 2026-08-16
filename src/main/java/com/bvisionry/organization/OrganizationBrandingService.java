package com.bvisionry.organization;

import com.bvisionry.common.audit.AuditLogger;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.media.MediaQuotaPort;
import com.bvisionry.common.media.MediaUrlPort;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.common.tx.AfterCommit;
import com.bvisionry.organization.dto.BrandingResponse;
import com.bvisionry.organization.dto.UpdateBrandingRequest;
import com.bvisionry.organization.entity.Organization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Per-org white-label branding: one logo, one brand colour
 * (policy {@code decisions.white_label} — custom domains and branded email
 * senders are closed as out of scope and have no representation here).
 *
 * <p><strong>Why its own class and not a method on {@code OrganizationService}.</strong>
 * {@code ArchitectureRulesTest} rule 1 freezes cross-feature dependencies by
 * their full CONSTRUCTOR SIGNATURE, and {@code OrganizationService}'s
 * constructor carries five frozen cross-feature parameters. Adding a sixth
 * parameter changes that signature, which re-flags all five as NEW violations —
 * and the frozen store is never written. This class's constructor takes only a
 * same-package repository and shared-kernel ports ({@code common.media}), so it
 * introduces no feature→feature edge at all.
 */
@Service
public class OrganizationBrandingService {

    /**
     * The ONLY marker shape this org may store.
     *
     * <p>THE IDOR GUARD, and the reason it is a string check rather than a
     * lookup. An ORG_ADMIN can now upload media, and therefore can also PUT an
     * arbitrary {@code minio://bucket/key} string here. A stored marker is
     * resolved into a presigned GET for whatever key it names, so an
     * unconstrained marker mints a readable URL for ANY object in the shared
     * bucket — every other tenant's PDFs and videos. Org-scoped uploads land
     * under {@code org/<orgId>/branding/}, and a submitted marker is accepted
     * only when its prefix names the org being written. A caller who forges a
     * marker for another org is refused before anything is persisted.
     *
     * <p>The bucket segment is a wildcard: the bucket name is media-package
     * configuration, {@code MediaService} already refuses to resolve a marker
     * naming any other bucket, and mirroring it here would only create a second
     * place for the two to disagree. The trailing key segment is restricted to
     * the sanitised alphabet {@code MediaService.sanitizeFilename} produces, so
     * neither {@code ..} traversal nor a nested {@code /} can smuggle the key
     * back out of the org prefix — in particular
     * {@code .../branding/x/org/<self>/branding/y} is refused, which a
     * substring or SQL {@code LIKE} formulation would not be.
     *
     * <p>V154's CHECK mirrors this pattern. That constraint compares against
     * {@code id::text}, which Postgres renders LOWER-CASE, and {@code ~} is
     * case-sensitive — so the id comparison below must be case-sensitive too.
     * A case-insensitive compare here would let an upper-case-UUID marker pass
     * Java and then die on the constraint as a 500 instead of a clean 400.
     */
    private static final Pattern OWN_ORG_MARKER = Pattern.compile(
            "^minio://[A-Za-z0-9][A-Za-z0-9.-]{1,61}[A-Za-z0-9]"
          + "/org/([0-9a-fA-F-]{36})/branding/[A-Za-z0-9._-]{1,200}$");

    /**
     * Second, independent check on the colour. The DTO already carries
     * {@code @Pattern}, and V154 carries a CHECK; this is the middle layer,
     * because the value is interpolated into an SSR {@code <style>} block and
     * "six hex digits" has to be true of the value that reaches the database
     * regardless of which of the three layers a future refactor drops.
     */
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-f]{6}$");

    private final OrganizationRepository organizationRepository;
    private final MediaUrlPort mediaUrls;
    private final MediaQuotaPort mediaQuota;
    private final AuditLogger auditLogger;
    /**
     * Who is calling, without importing {@code auth}: the ratchet freezes
     * cross-feature calls per call site, so {@code SecurityUtils} is off limits
     * to new code. Same shared-kernel seam {@code AnnouncementService} uses.
     */
    private final CurrentUserAccessor currentUser;

    public OrganizationBrandingService(OrganizationRepository organizationRepository,
                                       MediaUrlPort mediaUrls,
                                       MediaQuotaPort mediaQuota,
                                       AuditLogger auditLogger,
                                       CurrentUserAccessor currentUser) {
        this.organizationRepository = organizationRepository;
        this.mediaUrls = mediaUrls;
        this.mediaQuota = mediaQuota;
        this.auditLogger = auditLogger;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public BrandingResponse get(UUID orgId) {
        return toResponse(requireOrg(orgId));
    }

    /**
     * Sets (or, with nulls, clears) the org's branding.
     *
     * <p>Audited through {@link AuditLogger}, the shared-kernel port
     * {@code SubOrganizationService} already uses — so the write leaves a trail
     * without the {@code organization -> audit} edge the ArchUnit ratchet
     * forbids. Only a real CHANGE is logged: a no-op PUT (the admin form saves
     * an untouched page) would otherwise fill the org's activity feed with
     * entries that record nothing.
     *
     * <p>The colour is logged verbatim, the logo as PRESENT/ABSENT rather than
     * as its marker: the marker is a presignable object key and an audit row is
     * read by more people, and more casually, than the column is.
     *
     */
    @Transactional
    public BrandingResponse update(UUID orgId, UpdateBrandingRequest request) {
        Organization org = requireOrg(orgId);
        String color = normalizeColor(request.brandColor());
        String marker = validateMarker(request.logoMarker(), orgId);

        String previous = org.getBrandLogoMarker();
        boolean logoChanged = !java.util.Objects.equals(previous, marker);
        if (logoChanged && marker != null) {
            // CONSUME-TIME quota reconciliation: this write is the first (and, in
            // this flow, only) point after the org-scoped upload where the server
            // acts on the object again. A presigned PUT never bound Content-Length,
            // so this re-checks the org's REAL, MinIO-recorded usage — including
            // the object just uploaded — rather than trusting whatever size was
            // declared at presign time. See OrgStorageQuotaService.reconcileAfterUpload.
            mediaQuota.reconcileAfterUpload(orgId, marker);
        }

        Map<String, Object> changes = new HashMap<>();
        if (!java.util.Objects.equals(org.getBrandColor(), color)) {
            changes.put("brandColor", color == null ? "default" : color);
        }
        if (logoChanged) {
            changes.put("logo", marker == null ? "removed" : "set");
        }

        org.setBrandColor(color);
        org.setBrandLogoMarker(marker);
        Organization saved = organizationRepository.save(org);
        if (logoChanged && previous != null) {
            // Release the object this org no longer references, or replacing a
            // logo consumes quota forever with no self-service way to free it.
            // After commit so a rolled-back write can never delete a
            // still-referenced object; the delete itself is best-effort.
            AfterCommit.run(() -> mediaQuota.releaseReplaced(orgId, previous));
        }
        if (!changes.isEmpty()) {
            auditLogger.log(currentUser.require().userId(), orgId,
                    OrgAuditActions.ORGANIZATION_BRANDING_UPDATED,
                    OrgAuditActions.ENTITY_ORGANIZATION, orgId, changes);
        }
        return toResponse(saved);
    }

    private BrandingResponse toResponse(Organization org) {
        String marker = org.getBrandLogoMarker();
        return new BrandingResponse(
                org.getBrandColor(),
                marker,
                marker == null ? null : mediaUrls.resolveUrl(marker));
    }

    /** Blank is the same as absent — both mean "clear it". */
    private static String normalizeColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!HEX_COLOR.matcher(normalized).matches()) {
            throw new BadRequestException("Brand colour must be a #rrggbb hex value");
        }
        return normalized;
    }

    private static String validateMarker(String raw, UUID orgId) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String marker = raw.trim();
        var match = OWN_ORG_MARKER.matcher(marker);
        // Case-SENSITIVE on purpose — see OWN_ORG_MARKER's javadoc: V154's CHECK
        // compares against a lower-case id::text with a case-sensitive operator,
        // so an upper-case-UUID marker waved through here would 500 on the
        // constraint instead of being refused as a 400.
        if (!match.matches() || !orgId.toString().equals(match.group(1))) {
            throw new BadRequestException(
                    "Logo must be an image uploaded to this organization "
                  + "(expected a minio:// marker under org/" + orgId + "/branding/)");
        }
        return marker;
    }

    /**
     * Guard-named per the {@code bareIdLoadsOnOrgOwnedReposRequireGuard}
     * convention. Tenancy itself is enforced a layer up by
     * {@code @orgAccess.isInOrg} on the controller — the {@code Organization}
     * aggregate has no org column of its own to assert against.
     */
    private Organization requireOrg(UUID orgId) {
        return organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgId.toString()));
    }
}
