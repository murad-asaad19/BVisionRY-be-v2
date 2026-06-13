-- Persist the member-id filter applied at Org Insight generation time so the
-- subsequent PDF/Excel exports can resolve names against the same subset the
-- AI was given. Empty / missing rows mean "no filter — include every evaluated
-- member" (legacy behaviour for reports created before this migration).
--
-- ON DELETE CASCADE on member_id keeps the list consistent when a user is
-- removed from the system (organization deletion hard-deletes its users) —
-- the report just shrinks rather than holding a dangling reference that
-- would surface as "unknown member" in PDF/Excel exports.

CREATE TABLE insight_report_member_ids (
    insight_report_id UUID NOT NULL REFERENCES insight_reports(id) ON DELETE CASCADE,
    member_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (insight_report_id, member_id)
);

-- No standalone index on insight_report_id: the composite primary key
-- (insight_report_id, member_id) already serves leading-column lookups via
-- its btree, so a separate single-column index would just duplicate it.
