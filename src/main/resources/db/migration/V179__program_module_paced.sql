-- ============================================================================
-- Not every module is a week.
--
-- The board and the journey label every column "<stage> NN" — "Week 01",
-- "Week 02" — because until now every module WAS a numbered stage of the drip.
-- A cohort modelled on a real programme also carries material that sits
-- outside the pacing: a welcome/orientation section at the front, a closing
-- letter at the back. Numbering those as weeks is simply wrong, and there was
-- no way to say so.
--
-- `paced` is that switch. True (the default, and every existing row) means the
-- module is a numbered stage and keeps its kicker. False means it is
-- always-on material: the kicker is suppressed and the stage numbering skips
-- it, so "Week 01" stays the first real week however much sits in front of it.
--
-- Deliberately NOT reusing lock_mode: an unpaced module may still be gated,
-- and a paced one may be UNLOCKED. The two answer different questions.
-- ============================================================================

ALTER TABLE program_modules
    ADD COLUMN paced boolean NOT NULL DEFAULT true;
