-- A survey can gift a public assessment to its respondents: when someone
-- completes the survey through its public link and leaves an email, we email
-- them a link to this public assessment. Nullable + ON DELETE SET NULL so
-- deleting the public link simply detaches the gift rather than blocking it.
ALTER TABLE surveys
    ADD COLUMN gift_public_assessment_link_id UUID
        REFERENCES public_assessment_links(id) ON DELETE SET NULL;
