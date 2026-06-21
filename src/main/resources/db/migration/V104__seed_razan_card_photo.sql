-- Attach Razan's portrait to the seeded business card. The image is served from
-- the web app's /public (web/public/razan-jalajel.jpg); photo_url is stored as a
-- plain path, which MediaService.resolveUrl passes through unchanged. Guarded so
-- it never overwrites a portrait an admin has since uploaded.
UPDATE business_cards
   SET photo_url = '/razan-jalajel.jpg',
       updated_at = NOW()
 WHERE slug = 'razan-jalajel'
   AND (photo_url IS NULL OR photo_url = '');
