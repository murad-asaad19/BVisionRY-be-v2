-- Phase I / spec §4 + §11: the reviewer's quality tag on an exercise submission.
--
-- METADATA ONLY. The participation score counts submitted-vs-assigned
-- (EngagementReadRepository.assignmentCounts reads es.status and nothing else);
-- no quality tag column may ever enter that formula, or a reviewer's judgement
-- would silently move a founder's score.
--
-- §7 stable keys: quality_tag_key is the identity (the platform "Scoring &
-- Labels" tag set), quality_tag_label is the label SNAPSHOT taken at tagging
-- time, so renaming or deleting a tag never rewrites what a reviewer said.
-- §7b: who tagged and when, re-stamped on every re-tag.
ALTER TABLE exercise_submissions
    ADD COLUMN quality_tag_key    TEXT,
    ADD COLUMN quality_tag_label  TEXT,
    ADD COLUMN quality_tagged_at  TIMESTAMP WITH TIME ZONE,
    ADD COLUMN quality_tagged_by  UUID REFERENCES users (id) ON DELETE SET NULL;
