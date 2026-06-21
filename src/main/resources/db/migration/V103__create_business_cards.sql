-- Digital business cards: public, QR-shareable contact cards (e.g. /card/razan-jalajel).
-- Stored as rows so super admins can add, edit, hide and reorder cards — and their
-- link buttons — without a code change. `links` is a jsonb array of
-- {icon, label, url} objects (icon ∈ WEBSITE|EMAIL|LINKEDIN|PHONE|LINK), rendered
-- as the pill buttons on the public card and edited as a unit in the admin console.

CREATE TABLE business_cards (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug          VARCHAR(80)  NOT NULL,
    name          VARCHAR(160) NOT NULL,
    title         VARCHAR(200),
    tagline       TEXT,
    -- Optional substring of `tagline` rendered bold (e.g. "build"). Mirrors the
    -- About page's goldFragment pattern: keeps the copy single-sourced.
    tagline_bold  VARCHAR(120),
    -- minio://bucket/key marker (uploaded via /api/v1/media) or an external URL;
    -- resolved to a browsable URL on read via MediaService.resolveUrl. Optional.
    photo_url     VARCHAR(512),
    links         JSONB        NOT NULL DEFAULT '[]'::jsonb,
    published     BOOLEAN      NOT NULL DEFAULT TRUE,
    display_order INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Slug is the public URL key (/card/{slug}) — unique, case-insensitive lookups
-- are done on the already-lowercased stored value.
CREATE UNIQUE INDEX idx_business_cards_slug ON business_cards (slug);
CREATE INDEX idx_business_cards_published_order
    ON business_cards (published, display_order, created_at);

-- Seed the first card (Razan Jalajel). The personal LinkedIn URL is a best guess
-- and the portrait is left for upload via the admin console — both are editable.
INSERT INTO business_cards (slug, name, title, tagline, tagline_bold, links, display_order)
VALUES (
    'razan-jalajel',
    'Razan Jalajel',
    'Co-founder & CEO',
    'Stop guessing. Know which founders are actually ready to build.',
    'build',
    '[
        {"icon": "WEBSITE",  "label": "Website",            "url": "https://www.bvisionry.com"},
        {"icon": "EMAIL",    "label": "razan@bvisionry.com", "url": "mailto:razan@bvisionry.com"},
        {"icon": "LINKEDIN", "label": "My Linkedin",        "url": "https://www.linkedin.com/in/razan-jalajel"},
        {"icon": "LINKEDIN", "label": "Bvisionry Linkedin", "url": "https://www.linkedin.com/company/bvisionry"}
    ]'::jsonb,
    0
);
