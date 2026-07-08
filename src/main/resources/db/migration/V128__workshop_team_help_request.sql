-- A team's "we need help" ping from the task timer. Set by any team member
-- once their soft time budget runs out; cleared when the admin dismisses the
-- alert on the live board. Null = no open help request.
ALTER TABLE workshop_teams
    ADD COLUMN help_requested_at timestamptz;
