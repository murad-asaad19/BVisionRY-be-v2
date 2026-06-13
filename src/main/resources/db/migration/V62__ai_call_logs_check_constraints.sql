-- Lock down the {@code ai_call_logs.status} column to the values the
-- application enum {@link com.bvisionry.common.enums.AICallStatus} actually
-- emits. NOT VALID + VALIDATE keeps the lock weak during attach so an
-- existing row with an unexpected status (e.g. from a prior schema) won't
-- block the migration; the second statement still rejects future writes.
ALTER TABLE ai_call_logs
    ADD CONSTRAINT ck_ai_call_logs_status
    CHECK (status IN ('SUCCESS', 'FAILED'))
    NOT VALID;

ALTER TABLE ai_call_logs
    VALIDATE CONSTRAINT ck_ai_call_logs_status;
