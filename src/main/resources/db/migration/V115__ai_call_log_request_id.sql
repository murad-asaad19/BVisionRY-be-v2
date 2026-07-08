-- Correlates AI calls with the originating request; matches RequestCorrelationFilter's 64-char safe-id cap.
ALTER TABLE ai_call_logs ADD COLUMN request_id varchar(64);
