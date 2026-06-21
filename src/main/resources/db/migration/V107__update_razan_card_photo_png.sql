-- Swap Razan's portrait to the transparent cutout PNG (web/public/razan-jalajel.png).
-- The card design renders the portrait overflowing the gold disc, so it needs a
-- background-removed PNG rather than the original .jpg. Guarded so it only touches
-- the seeded .jpg value, never an admin-uploaded portrait.
UPDATE business_cards
   SET photo_url = '/razan-jalajel.png',
       updated_at = NOW()
 WHERE slug = 'razan-jalajel'
   AND photo_url = '/razan-jalajel.jpg';
