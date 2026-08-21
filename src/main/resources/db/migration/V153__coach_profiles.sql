-- =============================================================================
-- V153__coach_profiles.sql — the coach's own bookable profile
-- =============================================================================
-- Roadmap §14.3 / policy `calendar: INTEGRATE_CAL_COM` — we never build
-- booking. The coach stores the Cal.com booking page they already own; the
-- founder's browser goes there directly. Nothing about a booking is stored
-- here and no founder data is transmitted anywhere.
--
-- Grain: ONE row per coach, PK = the coach's own users(id). No org_id on
-- purpose — the row belongs to the PERSON, not to a tenant, and every query
-- that needs the tenant already joins `users` (which carries
-- organization_id). Denormalising the org here would add a column no query
-- reads and would drag the row into the org-owned bare-ID ArchUnit rule for
-- no gain, since the only writer is the coach themselves and the id comes
-- from the authenticated principal, never from a request.
--
-- Erasure/FK design (PersonalDataCoverageTest): coach_id CASCADE — the
-- profile is personal data ABOUT the coach and nobody else, so it dies with
-- the users row. It is exported as its own section in PersonalDataRepository.
--
-- booking_url is nullable and unconstrained at the DB level: the host
-- allowlist (https + cal.com / *.cal.com, dot-boundary matched) is enforced
-- on WRITE by @CalComBookingUrl, where a rejection can carry a message the
-- coach can act on. A CHECK here would duplicate a rule that has to live in
-- Java anyway (URI parsing, not string matching — `https://cal.com@evil.com`
-- is the case a LIKE pattern gets wrong).
-- =============================================================================

CREATE TABLE coach_profiles (
    coach_id    uuid        NOT NULL,
    booking_url text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_coach_profiles PRIMARY KEY (coach_id),
    CONSTRAINT fk_coach_profiles_coach FOREIGN KEY (coach_id)
        REFERENCES users (id) ON DELETE CASCADE
);
