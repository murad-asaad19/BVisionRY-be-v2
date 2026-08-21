-- =============================================================================
-- V203 — RESOLVED narrows to its flattering half, on purpose this time.
--
-- Since V192, RESOLVED has been the only kind with two "after" outcomes: a
-- growth edge that became a strength, OR one that simply stopped being
-- mentioned. The second reading redesign (Razan, Aug 2026) calls this out as
-- the core confusion: the founder-facing "Closed the gap" card celebrates,
-- and a growth edge that quietly vanished has not earned a celebration.
--
-- The taxonomy matrix after this migration:
--
--   BEFORE \ AFTER   Strength          Growth edge   Not present
--   Strength         CARRIED_FORWARD   REGRESSED     FADED
--   Growth edge      RESOLVED          PERSISTED     PERSISTED (unverified)
--   Not present      NEW               EMERGED       —
--
-- Growth edge → absent lands on PERSISTED rather than a new kind: absence of
-- mention is not resolution, so the honest default is "still open" — and every
-- narrative already passes DRAFT → APPROVED coach review, which is the
-- spec's "flag it for the coach" requirement with no new machinery.
--
-- GUARDED exactly as V192/V202: an installation whose admins have edited the
-- template keeps their wording and merges by hand from the release notes. The
-- binding half of the rule also lives in ShiftNarrativeService (RESOLVED_NOTE),
-- which reaches customised installations too.
-- =============================================================================

UPDATE prompt_templates
SET content = replace(content,
        '- RESOLVED — a growth edge in BEFORE that is absent from AFTER, or now '
            || 'appears there as a strength.',
        '- RESOLVED — a growth edge in BEFORE that now appears in AFTER as a strength. '
            || 'Absence alone is not resolution: a growth edge in BEFORE that is simply '
            || 'absent from AFTER stays PERSISTED unless the AFTER text or the ACTIVITY '
            || 'section shows it became a strength.')
WHERE prompt_type = 'SHIFT_NARRATIVE'
  AND content LIKE '%- RESOLVED — a growth edge in BEFORE that is absent from AFTER, or now appears there as a strength.%'
  AND NOT EXISTS (SELECT 1 FROM prompt_template_revisions r
                  WHERE r.template_id = prompt_templates.id);

-- The member-level synthesis (V193) restates the vocabulary in its own wording
-- and needs the same narrowing, or the summary can celebrate what the per-pillar
-- narrative no longer would.
UPDATE prompt_templates
SET content = replace(content,
        '- RESOLVED — a growth edge named across the earlier readings that no longer '
            || 'appears, or now appears as a strength.',
        '- RESOLVED — a growth edge named across the earlier readings that now appears '
            || 'as a strength. Absence alone is not resolution: a growth edge that simply '
            || 'stops appearing stays PERSISTED unless the later readings show it became '
            || 'a strength.')
WHERE prompt_type = 'MEMBER_GROWTH_SUMMARY'
  AND content LIKE '%- RESOLVED — a growth edge named across the earlier readings that no longer appears, or now appears as a strength.%'
  AND NOT EXISTS (SELECT 1 FROM prompt_template_revisions r
                  WHERE r.template_id = prompt_templates.id);
