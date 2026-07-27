-- V156 — model the three SOLD plans on subscription_tier.
--
-- Before: FREE | PREMIUM, pinned by V1's inline CHECK (auto-named
-- organizations_subscription_tier_check by Postgres).
-- After:  FREE | STARTER | GROWTH | FOUNDER_SUCCESS, matching the pricing page
--         (Starter $299 · Growth $599 · Founder Success = Contact Sales).
--
-- PREMIUM was never a sold plan, only a stand-in for "this org pays", so it is
-- retired rather than kept as a value nothing can be on. Every stored PREMIUM
-- row becomes GROWTH: it is the HIGHER of the two self-serve ceilings
-- (1 cohort/month vs Starter's 1/quarter), so no paying customer wakes up with
-- less capacity than they had yesterday. Landing them on STARTER would have
-- been a silent downgrade of live accounts.
--
-- Order matters: the old CHECK forbids 'GROWTH', so it must be dropped before
-- the UPDATE and the new one added after.

ALTER TABLE organizations
    DROP CONSTRAINT IF EXISTS organizations_subscription_tier_check;

UPDATE organizations
SET subscription_tier = 'GROWTH'
WHERE subscription_tier = 'PREMIUM';

ALTER TABLE organizations
    ADD CONSTRAINT organizations_subscription_tier_check
        CHECK (subscription_tier IN ('FREE', 'STARTER', 'GROWTH', 'FOUNDER_SUCCESS'));
