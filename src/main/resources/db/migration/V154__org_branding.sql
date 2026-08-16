-- =============================================================================
-- V154__org_branding.sql — per-org white-label branding (roadmap §7 / policy
-- decisions.white_label: logo + colours only; custom domains and branded email
-- senders are CLOSED as out of scope and are not represented here).
-- =============================================================================
-- Expand-only: two NULLABLE columns on `organizations`, no table, no drop, no
-- rename, no backfill. NULL means "no branding configured" and every existing
-- org therefore keeps rendering the stock Bvisionry theme with no data
-- migration and no "empty string means default" branch in Java.
--
-- WHY COLUMNS AND NOT A SETTINGS TABLE. Same reasoning V149 recorded for
-- inactivity_nudge_days: there is no per-org key/value store in this schema
-- (platform_settings is global, program_settings is keyed by cohort), and a
-- table holding two scalars for one tenant would be the only rows in it. The
-- established per-org knob is a typed column with a CHECK.
--
-- brand_color
--   The ONE brand colour an org admin picks. Every *-foreground token is
--   DERIVED from it by WCAG relative luminance in the web app, so an
--   unreadable palette is structurally unrepresentable — the admin cannot pick
--   text colours at all. Stored lower-case `#rrggbb`; the CHECK is the
--   storage-layer half of the CSS-injection defence (the value is interpolated
--   into a <style> block during SSR, so "it is six hex digits" has to be true
--   of the DATA, not merely asserted by whichever code path last wrote it).
--
-- brand_logo_marker
--   A `minio://bucket/objectKey` marker, exactly like content.video_url and
--   business_cards.photo_url. It is resolved to a short-lived presigned GET URL
--   per request.
--
--   THE IDOR GUARD. An ORG_ADMIN can now upload media (previously
--   SUPER_ADMIN/INSTRUCTOR only), which means an ORG_ADMIN can also POST an
--   arbitrary marker string here. Because a marker is resolved into a presigned
--   GET for whatever object key it names, an unconstrained marker would mint a
--   readable URL for ANY object in the shared bucket — every other tenant's
--   PDFs and videos. Org-admin uploads therefore land under an
--   `org/<orgId>/branding/` key prefix, and this CHECK makes a marker outside
--   the row's OWN org prefix unstorable. The service validates the same rule
--   before it ever gets here; this constraint is the layer that survives a
--   future write path that forgets to.
--
--   A REGEX, NOT `LIKE`. The obvious spelling —
--   `LIKE 'minio://%/org/' || id || '/branding/%'` — is a CONTAINS, not a
--   prefix: SQL's `%` matches `/` too, so
--   `minio://b/org/<victim>/branding/x/org/<self>/branding/y` satisfies it
--   while naming the victim's object. `[^/]+` on both the bucket and the final
--   key segment pins the path SHAPE, so the org id can only ever be the second
--   path segment and the key cannot nest. It mirrors OWN_ORG_MARKER in
--   OrganizationBrandingService character for character; keep the two in step.
--   `id::text` renders a UUID lower-case and `~` is case-sensitive, which is
--   why the Java guard compares the id case-SENSITIVELY as well — a marker this
--   constraint would reject must be refused up there as a 400, not arrive here
--   and become a 500.
--
--   The bucket segment is left as a wildcard on purpose: the bucket name is
--   deployment configuration owned by the media package (bvisionry.minio.bucket)
--   and MediaService already refuses to resolve a marker naming any bucket
--   other than the configured one. Duplicating it here would only add a way for
--   the two to disagree.
-- =============================================================================

ALTER TABLE organizations
    ADD COLUMN brand_color VARCHAR(7);

ALTER TABLE organizations
    ADD COLUMN brand_logo_marker VARCHAR(512);

ALTER TABLE organizations
    ADD CONSTRAINT ck_organizations_brand_color
        CHECK (brand_color IS NULL OR brand_color ~ '^#[0-9a-f]{6}$');

ALTER TABLE organizations
    ADD CONSTRAINT ck_organizations_brand_logo_marker_is_own_org
        CHECK (brand_logo_marker IS NULL
               OR brand_logo_marker ~ ('^minio://[^/]+/org/' || id::text || '/branding/[^/]+$'));
