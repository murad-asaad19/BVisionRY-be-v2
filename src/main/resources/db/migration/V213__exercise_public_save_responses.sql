-- Whether the public link STORES what respondents fill in. When false the
-- taker keeps work in the browser only (localStorage) and offers no submit,
-- so nothing an anonymous visitor types ever reaches the server.
ALTER TABLE exercise_templates
    ADD COLUMN save_public_responses BOOLEAN NOT NULL DEFAULT TRUE;
