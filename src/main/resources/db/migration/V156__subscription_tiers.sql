-- =============================================================================
-- V156__subscription_tiers.sql — the three SOLD plans on subscription_tier
-- =============================================================================
-- Before: FREE | PREMIUM, pinned by V1's inline CHECK (auto-named
-- organizations_subscription_tier_check by Postgres).
-- After:  FREE | STARTER | GROWTH | FOUNDER_SUCCESS, matching the pricing page
--         (Starter $299 · Growth $599 · Founder Success = Contact Sales).
--
-- Expand-only, following V147 (COACH role) exactly:
--   1. Widen the CHECK to admit the three new plans. 'PREMIUM' STAYS in the
--      allowed set on purpose — the contraction that drops it from the CHECK is
--      a later, operator-era migration. With the Java enum constant removed in
--      the same release, nothing can WRITE the value any more; keeping it in
--      the CHECK just makes this migration a pure expansion.
--
--      Not cosmetic: under a rolling deploy the old instance is still serving
--      after Flyway has run for the new one, so an admin flipping an org's tier
--      through the pre-deploy UI still writes 'PREMIUM'. Dropping it here turns
--      that into a constraint violation on a live request; keeping it lets the
--      write land and be cleaned up later.
--
--      Consequence the CONTRACTION must honour: that same deploy window can
--      mint new 'PREMIUM' rows after step 2 below has already run, so the
--      contraction has to re-run the UPDATE before narrowing the CHECK. A row
--      left on 'PREMIUM' throws on read — SubscriptionTier no longer has the
--      constant — and 500s every organization surface.
--
--   2. Map every stored PREMIUM row to GROWTH. PREMIUM was never a sold plan,
--      only a stand-in for "this org pays", so it is retired rather than kept
--      as a value nothing can be on. GROWTH is the HIGHER of the two self-serve
--      ceilings (1 cohort/month vs Starter's 1/quarter), so no paying customer
--      wakes up with less capacity than they had yesterday. Landing them on
--      STARTER would have been a silent downgrade of live accounts.
--
-- Order matters: V1's CHECK forbids 'GROWTH', so it must be dropped before the
-- UPDATE and the widened one added after.
-- =============================================================================

ALTER TABLE organizations
    DROP CONSTRAINT IF EXISTS organizations_subscription_tier_check;

UPDATE organizations
SET subscription_tier = 'GROWTH'
WHERE subscription_tier = 'PREMIUM';

-- 'PREMIUM' is the retired value, kept only for the deploy window (see header).
ALTER TABLE organizations
    ADD CONSTRAINT organizations_subscription_tier_check
        CHECK (subscription_tier IN
               ('FREE', 'STARTER', 'GROWTH', 'FOUNDER_SUCCESS', 'PREMIUM'));
