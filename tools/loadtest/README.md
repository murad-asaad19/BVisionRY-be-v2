# Load testing

One script, for one path: the **anonymous public-assessment funnel**. That is the
marketing funnel — a QR code on a banner, a link in a newsletter — and the only
flow where a stranger can drive the platform end to end with no account. A single
campaign can put a thousand first-time visitors on it inside an hour. Everything
else is behind a login, and therefore behind a population we already know.

Nothing here is wired into CI or the build. k6 is a static binary, not a
dependency of either repo, so it cannot rot a lockfile or slow a test run. Load
testing is a thing you do before a launch, deliberately, against a sandbox lane —
not a thing that happens on every push.

## Run it

```bash
# 1. A lane, never dev (:5432/:8080) and never production.
bash docker/sandbox/sandbox.sh up 1
set -a; source docker/sandbox/agent-1.env; set +a
cd backend && ./mvnw spring-boot:run          # honours SERVER_PORT from the env

# 2. A published public-assessment link. Admin console →
#    Public assessments → create → copy the link token (a UUID).

# 3. Go.
k6 run \
  -e BASE_URL=http://localhost:8181 \
  -e LINK_TOKEN=<uuid> \
  -e PROXY_SECRET=$BVISIONRY_PROXY_SHARED_SECRET \
  backend/tools/loadtest/public-assessment.js
```

Install k6: `winget install k6` · `brew install k6` · or grab the binary from
<https://k6.io/docs/get-started/installation/>.

| Variable | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://localhost:8181` | Lane 1's API port. Lane *n* is `8180+n`. |
| `LINK_TOKEN` | — | **Required.** Published public-assessment link token. |
| `PROXY_SECRET` | *(unset)* | See below. Without it the run measures the rate limiter. |
| `VUS` | `20` | Concurrent simulated respondents at plateau. |
| `DURATION` | `1m` | Plateau length. |

## `PROXY_SECRET` is not optional in practice

Session-create is capped at **5 requests per minute per client IP**
(`bvisionry.rate-limit.public-assessment.requests-per-minute`). A load generator
is one client IP. So without further care the naive load test measures the rate
limiter, returns a wall of 429s, and tells you nothing about whether the app can
serve a cohort.

`ClientIpResolver` trusts an `X-Bvisionry-Client-Ip` header only when
`X-Bvisionry-Proxy-Secret` matches the backend's configured shared secret — that
pair is how the Vercel BFF attributes traffic to the real respondent, because
public traffic reaches this API through the BFF rather than browser-direct. The
script sends the same pair, so each virtual user occupies its own bucket and the
traffic shape under test is the production one.

Run it without the secret and it warns you, loudly, that the numbers describe the
limiter. It does not quietly pretend otherwise.

## What a pass looks like

The thresholds are in the script, not in a reviewer's head:

| Threshold | Why that number |
|---|---|
| `http_req_duration{scenario:funnel} p(95) < 1500ms` | A taker on conference wifi should never wonder whether the page is broken. |
| `http_req_failed{scenario:funnel} rate < 1%` | Anything above this is the app failing, not the limiter working — the limiter's 429s are counted separately. |
| `submit_duration p(95) < 3000ms` | Submit dispatches the AI evaluation **asynchronously** and the taker polls `/status`. A submit that creeps toward model latency means that dispatch became synchronous, which is the regression this scenario is shaped to catch. |
| `limiter_held rate > 50%` | The abuse scenario must actually be refused. If this drops, per-IP rate limiting has silently stopped working — a green load test with a broken limiter is worse than no load test. |

Two scenarios run concurrently on purpose. `funnel` is a cohort launch: many
distinct people, each going through once. `limiter` is one IP hammering
session-create without pause. Running them together tests the property that
actually matters — **throttling one abuser does not degrade everybody else** —
which neither scenario proves alone.

## Deliberately not covered

- **The authenticated app.** Bounded, known population, behind a login.
- **The AI evaluation itself.** It is an async fan-out onto a provider we do not
  control and pay per call; load-testing it would bill real money to measure
  someone else's capacity. The funnel asserts that submit *returns* promptly,
  which is the part that is ours.
- **CI integration.** A load test in CI is a slow, flaky test that no one reads.
  Run this before a launch and when the funnel changes.
