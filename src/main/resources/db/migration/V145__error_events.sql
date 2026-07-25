-- Error tracking — the run's regression signal.
--
-- ONE in-box table aggregating unhandled exceptions from BOTH tiers: the
-- backend's GlobalExceptionHandler 500 catch-all and the Next.js web app
-- (server errors via `onRequestError`, browser errors via `global-error.tsx`).
-- Deliberately in-box rather than a hosted tracker: stack traces and request
-- paths of a founder-assessment product are user data, and shipping them to a
-- third party is forbidden by agent-policy.yml -> never_auto_decide.
--
-- Insert-only and append-only. `created_at` IS the occurrence time (web reports
-- arrive within milliseconds), so there is no separate occurred_at to keep in
-- sync. Read path is SUPER_ADMIN-only and always "most recent first", which is
-- what the single index serves.

CREATE TABLE error_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source VARCHAR(16) NOT NULL CHECK (source IN ('BACKEND', 'WEB')),
    exception_type VARCHAR(255) NOT NULL,
    message TEXT,
    stack_trace TEXT,
    request_path TEXT,
    request_method VARCHAR(16),
    -- The join key, and only ever an X-Request-Id (see RequestCorrelationFilter).
    -- Both tiers source the REAL inbound header rather than minting their own:
    -- `serverFetchJson` forwards it to the backend and `onRequestError` reads it
    -- off the failing request, so a WEB row and the BACKEND row beneath it carry
    -- the same value and
    --   SELECT ... GROUP BY request_id HAVING count(DISTINCT source) > 1
    -- returns the pair.
    --
    -- SCOPE, honestly: this joins whenever the request ARRIVED with an
    -- X-Request-Id. Always true for browser->BFF->backend API calls (the BFF mints
    -- one per proxied request), and for any page render behind a proxy that stamps
    -- one. A page render that arrives WITHOUT one has no shared id to inherit, so
    -- its rows land unjoined rather than joined to something wrong.
    -- ponytail: no edge stamp - `proxy.ts` matches only /app, /courses and
    -- /api/bff, and widening it to every route is an app-wide change to a
    -- security-sensitive file, not this ticket's. Stamp X-Request-Id there when
    -- unjoined page-render rows actually get in the way.
    request_id VARCHAR(64),
    -- Next.js's own error digest, a DIFFERENT namespace from request_id: it links
    -- a browser row back to the server render that produced it. Kept in its own
    -- column precisely so it cannot be mistaken for a join key.
    digest VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_error_events_created_at ON error_events (created_at DESC);
-- The read endpoint's only filter is `source`, always ordered by created_at DESC;
-- the composite lets that plan index-only instead of filtering the whole table.
CREATE INDEX idx_error_events_source_created_at ON error_events (source, created_at DESC);
-- Partial index: the correlation join only ever looks at rows that HAVE an id.
CREATE INDEX idx_error_events_request_id ON error_events (request_id) WHERE request_id IS NOT NULL;
