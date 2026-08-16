-- =============================================================================
-- V178__coach_profile_headline_bio_and_photo.sql
-- A coach is a PERSON before they are a booking link
-- =============================================================================
-- V153 gave coach_profiles exactly one column — the Cal.com page the coach
-- already owns — because the only surface consuming it was a one-line card on
-- the founder's home ("Book a session with X"). Coaches Corner is the surface
-- that ticket deliberately did not build: a founder lands on it to decide WHICH
-- coach to spend an hour with, and a name plus a URL is not enough to decide
-- anything. So the row grows the three things that answer "who am I booking?":
--
--   headline   varchar(160) — one line under the name ("Fundraising & GTM").
--                             160 because it is a subtitle, not a paragraph;
--                             the bio is where prose belongs.
--   bio        text         — a serialised tiptap document, the same shape and
--                             the same TEXT column style as
--                             exercise_templates.description (V177) and
--                             content.body. No backfill clause here, unlike
--                             V177: the column is NEW, so there is no plain
--                             text already in it to lift into a document.
--   photo_url  varchar(500) — mirrors exercise_templates.cover_image_url /
--                             courses.cover_image_url: it holds either a
--                             `minio://bucket/key` marker resolved at read time
--                             through MediaUrlPort, or an external URL.
--
-- All three are NULLABLE with no default and no backfill. Every existing row is
-- a coach who published a link and nothing else, and that stays a legitimate,
-- fully-rendered state — the card names them and shows the booking button; the
-- profile fields are additive polish, never a gate on being bookable.
--
-- Still no booking data and still no scheduler: policy
-- `calendar: INTEGRATE_CAL_COM` is unchanged by this migration (see V153's
-- header). The founder's browser goes to cal.com; we store who the coach is,
-- never when anyone met them.
-- =============================================================================

ALTER TABLE coach_profiles
    ADD COLUMN headline  varchar(160),
    ADD COLUMN bio       text,
    ADD COLUMN photo_url varchar(500);
