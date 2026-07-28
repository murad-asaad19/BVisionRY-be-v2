-- Repair-attempt history on the AI call audit trail.
--
-- Before: one ai_call_logs row recorded only the FINAL state of a call — the
-- original system_prompt/user_message and the last raw_response. The guardrail's
-- repair round-trips happen inside LangChain4j, so a call that was reprompted
-- (once, or until the budget was exhausted) left no record of the intermediate
-- drafts or the corrective messages sent back. Diagnosing why a model ended up
-- where it did required a provider-side transcript.
--
-- After: attempts counts the model round-trips (1 = clean first pass) and
-- attempt_history carries the ordered per-attempt JSON — each draft, whether it
-- was ACCEPTED or REPROMPTED, and the corrective message that followed.
--
-- attempt_history is written only when a call actually made more than one attempt
-- (see AICallLogService): for a clean single-pass call the existing
-- system_prompt/user_message/raw_response columns already tell the whole story,
-- and duplicating them on every row would double the table's dominant growth term.

ALTER TABLE ai_call_logs ADD COLUMN attempts INTEGER;
ALTER TABLE ai_call_logs ADD COLUMN attempt_history TEXT;
