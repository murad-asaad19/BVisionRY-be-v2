-- Remove the unverified personal-LinkedIn link from the seeded Razan Jalajel card.
--
-- V103 seeded `https://www.linkedin.com/in/razan-jalajel` (label "My Linkedin")
-- and its own comment admits the URL is a "best guess". Publishing an unverified
-- personal-profile URL on a public, unauthenticated card risks linking to the
-- wrong person's profile (PII misattribution), so we drop ONLY that element.
--
-- KEEP: Website, Email (a business card's email is intended-public), and the
-- company LinkedIn (linkedin.com/company/bvisionry) — all verified/intended.
--
-- Idempotent + guarded: only touches slug='razan-jalajel', and only removes
-- array elements whose url equals the unverified personal-LinkedIn URL. Safe to
-- re-run (no-op once removed) and a no-op if the card or link is absent.
UPDATE business_cards
   SET links = (
           SELECT COALESCE(jsonb_agg(link), '[]'::jsonb)
             FROM jsonb_array_elements(links) AS link
            WHERE link ->> 'url' <> 'https://www.linkedin.com/in/razan-jalajel'
       ),
       updated_at = NOW()
 WHERE slug = 'razan-jalajel'
   AND links @> '[{"url": "https://www.linkedin.com/in/razan-jalajel"}]'::jsonb;
