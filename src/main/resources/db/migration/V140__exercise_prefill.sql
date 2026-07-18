-- Exercise prefill customizations:
--  * example_row      — a read-only sample row shown greyed out above the sheet
--  * starter_rows     — rows seeded into every new member submission
--  * allow_add_rows   — whether members may add/remove their own rows
--  * is_locked        — column is prefilled by the admin; members can't edit it
--  * is_starter       — marks seeded rows so they can't be deleted by members

ALTER TABLE exercise_templates
    ADD COLUMN example_row jsonb,
    ADD COLUMN starter_rows jsonb,
    ADD COLUMN allow_add_rows boolean NOT NULL DEFAULT true;

ALTER TABLE exercise_columns
    ADD COLUMN is_locked boolean NOT NULL DEFAULT false;

ALTER TABLE exercise_rows
    ADD COLUMN is_starter boolean NOT NULL DEFAULT false;
