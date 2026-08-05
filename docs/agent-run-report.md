# Agent run report — live state

> ## ⚠ THIS FILE IS STALE. READ `agent-decisions.md` FOR STATE.
>
> It stops at **wave 8 / 44 tickets**. The log went on to close waves 9, 10 and 11
> (**55 tickets**), declared the run closed, and several commits landed after
> that. Anything below about "what has landed" or "what runs next" is history.
>
> **Current state, verified 2026-08-01 against a green tree:** backend
> **1274/0/0** · web lint 0 / typecheck 0 / **1063 unit, 83 files** · e2e
> **157 passed ×2 consecutive**. Roadmap §7 and §10 delivered; §11 at 17 of 26,
> with the remainder split into external engagements and three named engineering
> items (see `roadmap.md` §11 and the decision log's §11 sweep entry).
>
> Specifically superseded here: §10's "six open operator decisions" and §11's
> item 5 (in-org founder anonymity) were **closed by the operator on 2026-07-27**
> — reading this file instead of the log is how that gets re-litigated, which has
> now happened once.
>
> **What this file is still good for: the doctrine.** §6 standing rules, §9 Gate 4
> doctrine, the lane/port procedure and the stale-artifact traps in §8 are all
> current and were all paid for. Read it for how to work, not for where things are.

Companion to `agent-policy.yml` (the constitution — immutable, human-amended only)
and `agent-execution-graph.md` (the design — how work is executed).

**This file is the mutable one.** It carries what has actually landed, what is
verified vs merely claimed, and what runs next. A fresh agent reads this file
plus the two above and needs **no conversation history** to resume.

Updated: 2026-07-25 — **FRESH START. A previous run's branches
(`agent/roadmap-phase0-lane1`, `agent/gdpr-export-delete-lane2`) were deleted by
the operator. Nothing from that run survives except the standing rules in §6.
Migration number V145 was used by the deleted run and never applied outside its
lane — it is available again; check `ls backend/src/main/resources/db/migration`
for the true next number before allocating.**

---

> **OPERATOR AMENDMENT (2026-07-25, in-session): parking is revoked.** Every
> ticket runs to completion. No effort cap on gate failures; validator vetoes
> are fix-cycled until satisfied, never abandoned; `never_auto_decide` items
> are asked of the operator in-session (deferred to the back of the queue if
> unreachable, never dropped). Evidence bar and hard constraints unchanged.
> Full text: `agent-decisions.md` → "OPERATOR AMENDMENT".

## 1. Resume protocol — do these in order

A fresh agent starts here. Nothing below assumes prior context.

```bash
# 0. Read the constitution and the design. Not the roadmap narrative.
#    backend/docs/agent-policy.yml
#    backend/docs/agent-execution-graph.md

# 1. Read the decision log: backend/docs/agent-decisions.md ON agent/integration.
#    NOT the base branch - integration is now AHEAD of it, and the base copy is
#    missing every wave-8/9 ruling. Reading the stale one is the recurring trap.

# 2. Create the worktree PAIR for the lane you claim (both repos, matching
#    branch names — an agent holding one repo is broken undetectably).
#    Branch naming: agent/<ticket-id>-lane<n>, off the base branches in §2.
git -C backend worktree add /e/projects/bvisionry-lms/.lanes/lane<n>/backend -b agent/<ticket>-lane<n> <backend-base>
git -C web     worktree add /e/projects/bvisionry-lms/.lanes/lane<n>/web     -b agent/<ticket>-lane<n> <web-base>

# 3. Claim the runtime lane. Never :5432 (dev), never lane 0 (operator's).
bash docker/sandbox/sandbox.sh up <n>

# 4. Source the lane env INLINE on every command — shell state does not persist
#    between tool calls, and an unsourced e2e run silently targets the wrong
#    server and still reports green (agent-policy.yml → gates.4_e2e.note).
set -a; source docker/sandbox/agent-<n>.env; set +a
```

Then: re-verify §3 before building on it, and pick up at §5.

---

## 2. Branch and lane state

| Repo | Base branch | Agent branches | **Integration (the deliverable)** |
|---|---|---|---|
| `backend` | `claude/production-roadmap-requirements-xp8zsf` @ `7cf9999` | All 17 landed tickets' lane branches kept as refs; none live | **`agent/integration` @ `e1b6647`** |
| `web` | `staging` @ `3dba121` | same | **`agent/integration` @ `fc9acd0`** |

**CORRECTION to a claim this report made in bold.** "THE ROADMAP IS COMPLETE"
is true of the **24-ticket policy backlog** (phases 0–4), which is what governed
this run. It is NOT true of `roadmap.md`'s own checklist, which carries **21
items and has NEVER been ticked** — including items that demonstrably landed
(e2e in CI, error tracking, diff coverage) and items that plainly did not
(shorten the 24h access-token TTL, complete the CSP nonce pipeline, and
`Terms/privacy review for AI evaluation of user content`). The checkboxes were
never maintained, so they are not evidence in either direction — but the
unqualified claim was mine and it was overstated. Reconciling that checklist is
open work.

**LIVE TIP: backend `aaac1f6` / web `0a04602` — 44 tickets. WAVE 8 CLOSED —
the operator's eight rulings are implemented.** Combination: backend
**1216/0/0/0**, frozen store 0 added / 4 removed, V155 applied 154→155, web
lint 0 / typecheck 0 / **776 tests, 60 files**, **Gate 4 155 passed ×2
consecutive**. Four validator lenses, zero vetoes, every lens found something
real. The invitation escalation is closed and verified LIVE rather than only
under MockMvc: same invitation id, `POST` → token present, `LISTING` →
`"token":null`.
**Three orchestration defects this wave were mine** — the §8 sync trap recurred,
I extended a permission that should have stayed a practice (and a worker was
right to refuse it), and I shipped an unverified CI guard twice. All three are
written up in the decision log rather than smoothed over.
**Still nothing pushed. The merge remains an operator action.**

**Superseded: backend `cb6e54c` / web `30c5e48` — 41 tickets. WAVE 7 CLOSED
(security backlog, operator-chosen). POLICY BACKLOG COMPLETE since wave 6.**
Wave 7 landed `download_token_scope` (40) + `showname_server_authority` (41).
Combination re-gated in full by the orchestrator: backend **1175/0/0/0**,
frozen store clean, web lint 0 / typecheck 0 / **776 tests, 60 files**,
**Gate 4 warm-up + 155 passed ×2 consecutive**. Six validator lenses, zero
vetoes, every lens found something real. Nothing pushed — the merge is still
an operator action, and there are now **eight** open operator decisions (§10,
plus the three wave-7 escalations in §11).

**Superseded: backend `2afc53b` / web `06e6acc` — 39 tickets. WAVE 6 CLOSED;
PHASE 4 COMPLETE; the 24-ticket POLICY BACKLOG complete — NOT roadmap.md
§§10–11, and four more waves followed this "completion".** All three final tickets
(`calendar_integration`, `saml_oidc_sso`, `white_label_theming`) landed and
re-gated as a combination: backend 1158/0/0/0, frozen store empty, web lint 0 /
typecheck 0 / 772 tests, **Gate 4 155 passed ×2 consecutive**. The governance
docs are now synced onto `agent/integration` (§8 closed). Nothing has been
pushed — LOCAL_COMMITS_ONLY holds; the merge is an operator action.
**Next action: the operator's, not an agent's — six open decisions in §10.**
Superseded: 36 at `c418963`/`f7b06ef` (wave 6 dispatched). Superseded: 35 at (wave 5 in flight:
platform_debts LANDED; dashboard_recommendations + auth_redirect_hardening
implementing). Wave 4 closed at 33:
combination **Gate 4 143/143 x2 consecutive** on the full tree (V151 verified
applied). Two operational lessons now standing: (1) if background task shells
are being killed externally, run suites FOREGROUND and launch servers DETACHED
(subshell &) — the kills take the shell, not the children; (2) a server whose
wrapper was killed mid-startup can hold a corrupted .next — the one-curl probe
is an anonymous BFF call: 500 where 401 belongs = corrupted cache; stop, clear,
relaunch. V151 consumed. Per-ticket Gate 4s all green.

**Superseded: backend `5ac05d1` / web `8f49333` — 31 tickets.** Adds
`gate_platform_items` (be `7448682`/web `399ec36`): the pillar route floor,
stories out of the coverage denominator, digest joins server+client error rows.
Gate 4 140/140 x2 (combination with denial anchors); integration backend suite
re-run green post-landing.

**Superseded: backend `6f3a2bd` / web `494120a` — 30 tickets.** Adds
`e2e_denial_anchors` (web `077845d`): denial-only e2e blocks anchored to live
identities + positive 404-body assertions (Gate 4 140/140 x2 consecutive).
Standing procedure addition: restart the web dev server every ~4 suite runs
(twice-observed degradation cadence: ECONNRESET / marketing 500s / slowdown).

**Superseded: backend `6f3a2bd` / web `ad5079a` — 29 tickets. WAVE 3 COMPLETE.**
Adds `autosave_wait_budget` (web `b4d207f`, orchestrator micro-commit),
`competency_band_axis` (web `e3241d1` — RULING 4 step 5 DONE) and
`pillar_course_mapping` (be `6f3a2bd` / web `ad5079a` — Phase 3 open, V150
consumed). Final combination **Gate 4 140/140** (every wave-3 spec in one
suite; V150's first lane application). All wave-3 zones released. Next
eligible: `ux_p0` (webapp), `auto_enrolment` (assessment — SINGLE AGENT,
sequential, per the execution graph §8; consumes the landed mapping;
courseState refusal is ITS obligation, pinned in the mapping DTO), then
`dashboard_recommendations`. Platform items (a)-(f) in the decision log's
wave-3 close entry await the next intake. V151 = next unallocated migration.

**Superseded tip: backend `f9cb82f` / web `8f57a5a` — 27 tickets.** Adds
`sweep_route_boundaries` (web `43a5a0f`): the `(app)` error boundary with
three-layer evidence (jsdom 9 cases + e2e spec + **Gate 4 135/135** — the new
spec's first execution). Phase 2 sweeps: 2 of 3 done (`ux_p0` remains).

**Superseded tip: backend `f9cb82f` / web `6a979b7` — 26 tickets.** Adds
`sweep_fe_test_backfill` (web `c8f434b`): jsdom test project, 493 unit tests
(was 334), and a RUN-WIDE gate change — `.tsx` changed lines now face the 70%
coverage gate except `{page,layout,template,default,loading,not-found}.tsx`;
`error.tsx`/`global-error.tsx` are deliberately measured. Combination Gate 4
**134/134**. Coverage claims for `.tsx` work are now REAL — the old "`.tsx` is
excluded by construction" caveat is retired.

**Superseded tip: backend `f9cb82f` / web `4b2627b` — 25 tickets.** Adds
`gate4_env_hardening` (sandbox-profile limiter headroom + limiter unit tests +
recipient-scoped reset poll). **Gate 4 is 134/134 ×4 — the run's first fully
green full suites; the password-reset exception is RETIRED** (root cause: the
shared per-IP `authentication` bucket at the reset CONFIRM endpoint; full chain
in the decision log). Lanes now REQUIRE `SPRING_PROFILES_ACTIVE=dev,mock,sandbox`
(host env files updated; see the retroactive manifest below).

**Superseded tip: backend `2ca01ae` / web `711b39f` — 24 tickets.** Adds
`bell_badge_contrast` (web `37bf8e0`), `insights_entitlement_gate`
(`311d7d5`/`711b39f`) and `authz_archunit_rule` (`2ca01ae`). Combination
re-gate: web lint 0 · typecheck 0 · **334/334** unit · backend full suite
exit 0 (incl. the new Rule 6 across all 399 handlers + its falsification
test) · frozen-violations store `+8/−20` — verified against the amended
3-clause rule (every addition a byte-identical re-description of a removal,
guard never a violating type, net −12).

**THE GATE-4 EXCEPTION IS ROOT-CAUSED (2026-07-26, orchestrator investigation
on lane 1, integration tip).** Two independent facts, both read from code and
one observed live:
1. **Budget doctrine — FINAL (validator-corrected; three iterations to get
   here).** `RateLimitService` is **dual-mode**: with a Redis connection the
   windows are Redis TTL keys (`rl:<type>:<key>`) that **SURVIVE backend
   restarts** and are cleared by `FLUSHALL` on the lane's OWN Redis — so the
   original coach_console recording was right FOR LANES; without Redis (unit
   tests) it falls back to in-memory. Deleting `password_reset_tokens` rows
   never touches the budget (the table stores tokens, not counters) — that is
   why the recorded row-deletion experiment changed nothing. The reset budget
   is keyed per client IP AND per target email (`AuthController:158-159`).
   **The failing endpoint was a different limiter entirely:** the reset
   CONFIRM (`POST /reset-password`) sits on the generic `authentication`
   bucket (10/min per IP) shared with login/register/change-password.
2. **The full-suite-only failure is a Mailpit newest-message race.** The spec's
   poll fetches `?limit=1` (single newest message) and filters by recipient.
   Under `fullyParallel`, benchmarking/roi-report's FREE-org helpers send
   invitation emails mid-suite; observed live: the ROI invite and the reset
   email arrived **0.9s apart**. When any email lands AFTER the reset email,
   every poll iteration sees only that newer message, rejects it by recipient,
   and returns null until the 30s timeout — the reset email sits unread at
   index 1. Isolation = quiet mailbox = always green. Fix: scan the newest
   message **addressed to the target recipient** (bounded list scan), not the
   newest message globally. Fixed by `reset_spec_mailbox_race` (orchestrator
   micro-fix on `release-flows.spec.ts`, the reserved file).

**Orchestration lesson (self-inflicted, cost ~an hour):** `rm -rf .next`
while a dev server runs on that directory corrupts it — the BFF then returns
500 where it should return 401, and the symptom masquerades as an app authz
defect (an org admin "cannot list members" while the backend answers 200 to
the identical call). **Stop the server, THEN clear the cache.** Also:
`TaskStop` kills the shell, not the JVM/node child — verify the port is free
AFTER stopping a server task, not only before starting one.

**Superseded — previous tip: backend `bda504b` / web `0b672b9` — 21 tickets.** Wave 1 (17) +
`exercise_autosave_spec_hardening` + `competency_matrix` +
`inactivity_and_proactive_nudges` + `band_default_and_readable_thresholds`.
**Combined re-gate after the band landing: web lint 0 · typecheck 0 · 332/332
unit · backend full suite exit 0 · frozen-violations store byte-identical to
base.** Per-ticket Gate 4 before landing: competency **128/128 ×2**, nudges
**128/128 ×2**, band **132/132 ×3** — all on freshly-seeded lanes, cold `.next`,
ports verified free, and a real `"status":"UP"` health poll.
**In flight:** `insights_entitlement_gate` (lane 3 — both lenses PASS, backend
rebased onto the tip with the store diff intact; applying two product-honesty
fixes before landing), `authz_archunit_rule` (lane 2 — the audit's
highest-leverage finding: make authorization mechanically required instead of
opt-in per handler).
**Still owed (RULING 4 step 5):** the competency matrix re-axis onto ordinal
band POSITION — now UNBLOCKED, `/api/pipelines/{id}/bands` shipped with the band
ticket. Named owner needed.
**Gate-4 flake diagnosis REFINED (supersedes the earlier note):** the autosave
hardening genuinely fixed the stale-indicator RACE. A residual failure of
`release-flows:297` on run 1 after a cold `.next` is a different, narrower
defect — first-compile of that route plus the 1.5s autosave debounce can exceed
the `waitForResponse` 20s ceiling. It is a compile-budget interaction, not the
race. Recorded, not re-fixed (three warm runs clean). If it recurs, the fix is
the timeout budget, NOT the wait mechanism.

**Integration @ `e1b6647` (be) / `fc9acd0` (web)** — **17 tickets** (Phase 2 wave 1
landed: courses_qa `9db36f5` → announcements `c8971fc`/`45e1dbd` → roi
`e1b6647`/`fc9acd0`). Combined re-gate: web lint 0 / typecheck 0 / **311/311**
unit / CR-bytes 0 both repos; the two declared append-file conflicts resolved by
union + schema regen from the integrated backend (typecheck proves the pins);
backend full suite exit 0; integrated-stack e2e **124/124 twice consecutively**
(runs 3+4 on lane 3 serving the integration worktrees, default env — courses'
flag-OFF gate branch exercised; run 1 was also green; run 2's single failure was
the release-flows autosave race below). **WAVE 1 CLOSED.**

**UPDATE — the flake is FIXED and the exception is retired.**
`exercise_autosave_spec_hardening` landed (web `a6e074f` → integration `0fcf664`,
backend zero-diff): Gate 4 **124/124 three times consecutively under live
multi-agent load**. Integration web tip is now `0fcf664`; backend stays
`e1b6647`. **No attributed Gate-4 exception exists anywhere in this run — strict
full-suite green is the bar.** The diagnosis below is kept because the root cause
(a stale indicator + a 1500ms debounce the test never waited on) is the general
pattern: never wait on an indicator that can already be in the target state.

**`release-flows.spec.ts:297` was a genuinely racy spec, NOT cold-cache** (the
earlier warmup theory is retired — 4 observations today incl. a failure after a
green run 1). Diagnosis from reading the test: after `blur()` it waits for
`getByText(/Saved/)`, but the header may already show a stale "Saved <time>"
pill, so the wait matches instantly while the autosave request is in flight and
the reload races it. Hardening micro-ticket `exercise_autosave_spec_hardening`
dispatched (lane 1, zone webtests): observe the actual save response before
reloading. Until it lands, a Gate-4 failure matching exactly this fingerprint
(only :297/:338 failing, value-persistence assertion) is attributable with a
rerun; anything else is real.

**Integration @ `51788e0` (be) / `6721ac0` (web)** — 14 tickets. Combined
re-gate after the gate4 landing: lint 0 · typecheck 0 · **280/280** unit ·
e2e **112/112 twice** (observed on lane 2 against this exact backend commit,
with the web tree byte-identical to the integrated result) · CR-byte check
clean on all changed files (the Phase 0 CRLF class re-verified, not assumed).

Benchmarking's cherry-picks were clean by construction (both branches forked
from the then-current integration tips) and the integrated trees verified
**byte-identical** to the gated lane branches (`git diff` = 0 both repos), so
the lane evidence — 749/0, 277/277, **112/112** e2e — IS the combination
evidence. No integration seams this time.

**Integration re-gated as a combination at `33d49b1`/`aeafb76`** (all 7 landed
tickets): backend **735/0** · web lint 0 / typecheck 0 / **270/270** unit ·
e2e **109/109** on freshly seeded lane 3 — strict green, no attributed
exceptions needed. The one integration seam surfaced exactly as predicted at
intake: founder_dashboard's deliberate `role === "MANAGER"` arm became TS2367
against the spine's narrowed role union — resolved by the orchestrator in
integration commit `aeafb76` (drop the arm; MANAGER holders are MEMBER as of
V147). The spine's new `SameUnion` contract pin would have caught it even if
the plan hadn't. `api-schema.d.ts` needed NO integration regen: backend
integration tree is byte-identical to the spine branch it was generated from.

**Phase 1 branches fork from `agent/integration`** (not the base branches) —
they need the landed gates and error store beneath them. Phase 0 lane worktrees
were retired (their stale dev servers killed, directories removed); the landed
branches remain as refs for review. Fresh pairs live at
`/e/projects/bvisionry-lms/.lanes/lane<n>/{backend,web}` (OUTSIDE both repos, so no linter walks one; a worker NEVER removes its own worktree).

Integration tip carries **all five landed Phase 0 tickets**, combined-regated
2026-07-26 at `47f2b4f` (be) / `2c81aae` (web): backend **716/0** · web
lint(blocking) + typecheck + **225/225** unit · e2e **89/89** on seeded lane 3.

**The integration re-gate earned its keep.** It caught a defect no lane could:
`scripts/scope-check.mjs` ships a `#!/usr/bin/env node` shebang, `core.autocrlf`
is true on this host, and `web/` had no `.gitattributes` — so git smudged the
file to CRLF *on checkout*, the shebang ended `node\r`, and vitest's ESM
transform threw `SyntaxError`, silently killing the gate's own test file. The
authoring lane passed only because its copy was written as LF and never
round-tripped through a checkout. Fixed by `web/.gitattributes` (`*.mjs text
eol=lf`, narrow on purpose); blobs were already LF in the index so
`--renormalize` was a verified no-op — one line, zero content churn.
Two orchestrator lessons, both binding from here:
- **`git diff --no-index` normalizes line endings and will report differing
  files identical. Use `cmp` when bytes matter.**
- **A `.gitattributes` fixes future checkouts only.** Existing worktrees keep
  their CRLF copies until re-materialized (`rm` the paths + `git checkout --
  .`). Integration was re-materialized; the operator's own `web/` checkout
  still holds CRLF copies (harmless today — nothing else imports a shebang
  `.mjs` through vitest).

**`agent/integration` is the review target**: every landed ticket applied in
landing order, full suite re-gated on the COMBINED result (backend **716/0**,
web lint/typecheck/unit green, e2e **89/89** on seeded lane 3; the one
first-run e2e failure was cold-compile flake on the fresh worktree — rerun
clean). The two tickets' only collision was the generated `api-schema.d.ts`;
resolved by re-running the contract pipeline against the integrated backend,
never by hand-merge. Worktrees: none - the orchestrator gates in the repo directories themselves, with `./mvnw CLEAN test` (a stale .class from a moved class has faked both a failure and, more dangerously, could fake a pass).
Future landings: cherry-pick onto integration → re-gate combined.

The backend base is docs-ahead of `staging` only (no code drift), so both repos
have code-identical bases — `pnpm gen:api` output on `staging` is valid for
either.

Worktree pairs live at `/e/projects/bvisionry-lms/.lanes/lane<n>/{backend,web}` (OUTSIDE both repos, so no linter walks one; a worker NEVER removes its own worktree). Lanes 0–3 are up and
healthy (`sandbox.sh status`); lane 0 is the operator's and is never taken.
Lane 1 → api `:8181` web `:3011`. Lane 2 → api `:8182` web `:3012`.

**Lane 1 is a chain, not a single ticket.** `error_tracking` →
`e2e_local_green` → `diff_coverage_and_lint_gate` → `scope_manifest_gate` all
run in the *same* worktree pair, stacking one commit per ticket. Branch name
stays `agent/error-tracking-lane1` for the whole chain (it is the lane's
branch, not the first ticket's).

### Migration numbers — allocated centrally, never claimed by a worker

| Version | Holder | State |
|---|---|---|
| ≤ V144 | committed history | applied |
| **V145** | `error_tracking` | consumed (landed) |
| **V146** | `gdpr_export_delete` | consumed (landed) |
| **V147** | `coach_console` | consumed (landed) |
| **V148** | `announcements` | consumed (landed) |
| **V149** | `inactivity_and_proactive_nudges` | consumed (landed — inactivity_nudge_threshold) |
| **V150** | `pillar_course_mapping` | consumed (landed) |
| **V151** | `auto_enrolment` | consumed (landed) |
| V152+ | unallocated | ask the orchestrator |

Rationale and the rule it replaces: `agent-decisions.md` → *"Migration numbering
is allocated centrally"*. The previous run-report note claiming V145 was
consumed is **stale** — that migration was never committed; `ls
backend/src/main/resources/db/migration` maxes at V144.

---

## 2a. WAVE 6 = PHASE 4 — intake manifests (dispatched 2026-07-27)

The roadmap's last three tickets. Three disjoint zones (auth / coaching /
webapp), so all three run concurrently. Stack point for all: **backend
`c418963` / web `f7b06ef`**. Branch pairs already created off `agent/integration`.

**Migration versions PRE-ALLOCATED by the orchestrator — a worker must use its
own number and no other** (three parallel tickets each needing DDL is the one
collision a lane cannot isolate):

| Ticket | Lane | Branch pair | Migration | Radius |
|---|---|---|---|---|
| `saml_oidc_sso` | 1 | `agent/saml-oidc-sso-lane1` | **V152** | L |
| `calendar_integration` | 2 | `agent/calendar-integration-lane2` | **V153** | M |
| `white_label_theming` | 3 | `agent/white-label-lane3` | **V154** | L |

### Design envelopes — RULED at intake, binding on the workers

Every ruling below was advised by an independent fresh-context reviewer that
saw only the verified facts and the policy (never an implementer's reasoning),
per the operator's standing "advise, implement, document" instruction. The
reviewer attached NON-NEGOTIABLE conditions; they are reproduced as constraints
in each envelope and a validator checks each one. Full text: decision log →
"PHASE 4 INTAKE — advisory rulings".

**Verified-by-running facts that shaped these (not recollection):**
- `spring-security-saml2-service-provider:7.0.2` IS on Maven Central; its
  transitive `org.opensaml:opensaml-saml-{api,impl}:5.1.6` are **NOT**. They
  resolve from `https://build.shibboleth.net/maven/releases/` (I ran both
  `dependency:get` calls; Central fails, Shibboleth succeeds). The pom declares
  no custom repositories today.
- Spring Security 7.0 reference (via context7, not memory): multi-tenant SSO is
  `RelyingPartyRegistrationRepository` (SAML) and `ClientRegistrationRepository`
  (OIDC), both DB-backable; `RelyingPartyRegistrations.collectionFromMetadata
  (InputStream)` is the documented "IdP metadata came from a database" path;
  `OpenSaml5AuthenticationProvider` already validates signature +
  AudienceRestriction + SubjectConfirmations. **The default
  `AuthorizationRequestRepository` is `HttpSession`-backed** — which collides
  with this app's `SessionCreationPolicy.STATELESS` main chain.
- ArchUnit ratchet, checked against the actual store: `organization`→`media`
  and `auth`→`media` have **no** frozen edge, so either import FAILS the build.
  `common`/`config` are the exempt seam (precedents: `common.coachaccess`,
  `common.gdpr.PersonalDataRepository`).

#### 1. `saml_oidc_sso` — enterprise SSO, additive, platform-configured
- **SAML ships.** Add the Shibboleth repo, releases-only
  (`<snapshots><enabled>false</enabled></snapshots>`), exact pinned versions.
  Rationale: `never_auto_decide` covers dependencies that *transmit user data
  off-box*; OpenSAML transmits nothing, and it is the canonical Spring-documented
  channel. Dropping SAML would instead have been a unilateral scope narrowing of
  a capability procurement confirmed. **This is flagged for human review at the
  next checkpoint** — see the operator backlog. Reversal: delete the repo block
  + dependency; OIDC untouched.
- **Never hand-roll assertion validation.** The existing Google flow skips JWS
  verification (defensible only because the id_token comes straight from
  Google's token endpoint over TLS). SAML has no such escape hatch: signature,
  audience, InResponseTo/replay and recipient checks come from library defaults.
- **Registrations are auth-owned**, `org_id` as a bare UUID (the
  `CoachAssignment` precedent) — no `auth`→`organization` entity import.
- **SUPER_ADMIN configures**; each registration carries a platform-verified
  `email_domain`, UNIQUE across registrations. ORG_ADMIN self-serve would need
  automated DNS-challenge verification, and a mis-verified domain IS cross-tenant
  account takeover.
- **THE THREE INVARIANTS (non-negotiable; dropping one flips the ruling to
  do-not-ship):** (1) the asserted email's domain must match the registration's,
  **exact label match, lowercased and IDN/punycode-normalised — never
  `endsWith`** (`evil-orgb.com` ≠ `orgb.com`); (2) **unconditional SUPER_ADMIN
  refusal** — a customer-controlled IdP may never mint a platform-admin session,
  in-domain or not; (3) **other-org refusal on EVERY login, not just JIT** —
  domain uniqueness stops two registrations claiming a domain, it does not stop
  an in-domain user who already belongs to a different org.
- **Provider-mismatch:** a domain-verified enterprise registration MAY
  authenticate a user whose stored `ssoProvider` is `GOOGLE`, and must NOT
  overwrite the stored value. Reason: verified domain ownership means the org
  already controls that mailbox (they could take the account via password reset
  today) — the assertion grants no new power. Without this, every existing
  Google user at the buying org is locked out on day one.
- **JIT provisioning: yes** — MEMBER only, into the registration's org; an
  assertion NEVER changes an existing user's role or org. Tripwire the worker
  must check and report: if any billing/entitlement path keys off org member
  count, STOP — JIT would then change what a customer is charged. (Pricing is
  per-cohort, so this is expected to pass; verify, don't assume.)
- **Additive, never enforced.** Password + Google keep working, so a broken IdP
  can never lock an org out. **Per-org "enforce SSO / disable password login" is
  ESCALATED, not built** — promise-shaped and lockout-risky; operator backlog.
- **Statelessness:** the main chain must stay `STATELESS` and token semantics
  must not change (session issuance reuses `AuthService.issueSession`, same
  cookies, same JWTs). Since the DSL's authorization-request store is
  session-backed, the expected shape is a SECOND `SecurityFilterChain`
  `securityMatcher`-scoped to the SSO handshake paths only (the
  `E2eSecurityConfig` second-chain precedent), so a session exists for the
  handshake and nothing else. The worker may pick a cookie-based repository
  instead if it proves cleaner — but the main chain stays stateless either way.

#### 2. `calendar_integration` — link-out, zero founder data off-box
- The coach stores their own Cal.com URL; the founder gets a "your coach" card
  (**no such surface exists anywhere today** — this ticket creates the first one)
  with a Book-a-session link opening in a new tab. We store nothing about
  bookings and transmit no founder data: the founder's own browser goes to
  Cal.com and they type their own details.
- **An in-app Cal.com embed would require escalation** (third-party script on a
  page holding sensitive founder data) — the link-out is the variant an agent may
  ship, and it is a faithful reading of `INTEGRATE_CAL_COM`. Embed + booking
  webhooks → operator backlog.
- Write endpoint: `PATCH /api/v1/coach/profile` on the existing coach-gated
  controller — identity IS the scope, and the `/api/v1/coach/**` route floor
  already exists, so all three defence layers are already in place.
- **Host allowlist `cal.com` + `*.cal.com`, dot-boundary matched** (`host ==
  "cal.com" || host.endsWith(".cal.com")` — a substring check lets `evilcal.com`
  through), composed on top of `@ValidExternalUrl`'s https/no-private-host
  semantics. This enforces the closed decision in code AND kills the phishing
  class: a compromised coach account cannot point founders at a harvester.
- Storage: nullable `coach_booking_url` on `users`. A `coach_profiles` table for
  one field is speculative normalisation; `users` already carries role-flavoured
  scalars (`avatar_url`, `sso_provider`). Extract when a third coach field arrives.

#### 3. `white_label_theming` — one brand colour, derived accessibly
- **`GET/PUT /api/organizations/{id}/branding`**, gated exactly like the existing
  `PUT /{id}/nudge-settings` (`SUPER_ADMIN or (ORG_ADMIN and
  @orgAccess.isInOrg(#id))`) — the established idiom for an ORG_ADMIN write on
  their own org record. Do NOT widen the wholesale org PUT.
- **NOT on `/api/auth/me`.** A separate branding GET, fetched in the app layout.
  Reversal asymmetry decides it: fields added to a hot, contract-pinned auth DTO
  get depended on and can never be cleanly removed. The performance argument for
  widening `/me` evaporates anyway — `session.orgId` is known before `/me`
  returns, so the layout fires both in parallel; and a branding response can be
  cached independently, which an auth response never can.
- **Logo upload: widen `MediaController` to ORG_ADMIN restricted to
  `kind=image`** — a bespoke endpoint in the `organization` feature is not
  buildable (the ratchet forbids `organization`→`media`), and a `common.branding`
  pass-through would be an abstraction with one caller.
  **THE IDOR GUARD (non-negotiable):** an ORG_ADMIN who can persist an arbitrary
  `minio://bucket/key` gets a presigned GET for *any object in the bucket* —
  every other org's PDFs and videos. So org-admin uploads land under a per-org
  key prefix (`org/{orgId}/branding/…`) and the branding endpoint validates the
  submitted marker by **string prefix against the caller's own org** — the
  organization feature validates a string and imports nothing from media, so the
  ratchet stays green. Keep the SVG exclusion; pin Content-Type on the presign so
  a spoofed "image" cannot be served as HTML. Per-org storage quota → backlog
  (bounded to 10MB/file, unbounded count, by an identified paying customer).
- **One colour, foregrounds DERIVED** from WCAG relative luminance — an
  unreadable palette must be structurally unrepresentable, which beats any
  admin-facing contrast validator. This is the correct reading of `colors: true`
  (the policy states no arity); more slots are additive later.
  `.dark` re-declares `--primary`, so the override must inject into **both**
  scopes, and a dark brand colour needs a luminance-adjusted dark-mode variant or
  it vanishes into the background.
- `(app)` only — the public marketing site stays Bvisionry-branded. `never_touch`
  still absolute.

## 3. What has landed — and what is only *claimed*

All five Phase 0 tickets are LANDED and combined-regated on `agent/integration`.
Phase 1 is in flight: `coach_console` (lane 1) and `founder_dashboard` (lane 2).

### ✅ `error_tracking` — LANDED

**backend `5e076b1` · web `50a2cc0`** (one commit per repo, amended through one
veto cycle; first submission `2fdd658`/`26f75c8` superseded). V145 consumed —
amended in-branch during the fix cycle (orchestrator-authorized; never left the
branch), so **lane DBs that applied the old V145 need `sandbox.sh reset <n>`**
(lane 1 already reset; the checksum mismatch was hit and cleared).

Landing evidence, cycle 2, all orchestrator-observed:
Gate 1 ✅ 693/0 · Gate 2 ✅ (springdoc reshuffle only; proxy-secret header
absent from spec) · Gate 3 ✅ = baseline · Gate 5 ✅ (22 be + 10 web paths, all
in-manifest) · Gate 4 🔶 36/15/34, failing set identical to pre-existing
baselines, zero new — strict green is `e2e_local_green`'s job.
Veto lenses cycle 2: tenant PASS · RBAC PASS (all 4 prior findings verified
resolved) · policy PASS.

What shipped: `error_events` table (V145: digest column, `(source, created_at
DESC)` + partial `request_id` indexes) · `common/errortracking/` (recorder with
bounded executor + DiscardPolicy, retention job cloning the AICallLog pattern,
SUPER_ADMIN-only GET, proxy-secret + per-IP rate-limited permitAll POST) ·
web: `instrumentation.ts` `onRequestError` + `global-error.tsx` → BFF ingest
(Server Action deleted after confused-deputy veto), `X-Request-Id` correlation
joining WEB↔BACKEND rows, `WebErrorReport` contract pin.

**Recorded residuals (non-blocking, operator's list):**
- Capability tokens (`/join/[token]`, `/invitations/[token]`, `/a/[token]`)
  can persist in `error_events.request_path` for the retention window if those
  routes crash; cheap redaction named by the tenant lens.
- Server-tier reports share one per-IP rate bucket (Vercel egress IP) — one
  tenant's crash loop can silence server-tier telemetry platform-wide.
- `error_events` is a durable PII-adjacent sink the GDPR erase flow does not
  reach (no user key on rows).
- No test pins the ingest 401-without-secret or the GET's SUPER_ADMIN gate.
- `request_id` on WEB rows is client-suppliable (correlation poisoning only).

### ✅ `e2e_local_green` — LANDED

**web `7c0a57b`** stacked on `50a2cc0`; backend untouched (Gate 1 skip
verified: worktree clean at `5e076b1`). The suite went from 14 recurring
failures + a flaky auth cascade to **89/89, zero skipped** — observed twice
consecutively by the orchestrator on a freshly seeded lane. Gate 3 observed
green and 5 lint warnings BETTER than baseline (260 vs 265). Validators
(narrowed panel for a web-test-only diff — logged): policy/test-integrity
**PASS** (every spec change re-derived against app code + git history; zero
weakened evidence; catalog branch proven byte-identical via `diff -w`),
RBAC-coverage **PASS** (role-boundary assertions verified intact; identity
per state file now proven, strictly stronger than before).

What shipped: storageState-per-role setup (5 identities, each proven by
email render), lane-derived Mailpit/base URLs (killed two dev-stack leaks),
honest catalog flag-branching, 4 stale specs corrected against git history,
`/solutions` coverage 10→11 routes, 6 app-side a11y fixes (computed WCAG
ratios) under logged manifest widening, bounded 429-literal backoff.

**Gate 4 doctrine — binds every future FE ticket (learned the hard way, 5
observed pairs):**
1. Run e2e against a **freshly seeded lane** (`sandbox.sh reset <n>`) — the
   suite is green on snapshot state; accumulated multi-run data renders
   surfaces (admin badges etc.) the snapshot doesn't, failing a11y scans.
2. `rm -rf .next` before the run whenever the commit touches `globals.css`
   or design tokens — a warm dev cache serves pre-fix CSS and fails a11y
   with phantom violations.
3. Budget: one suite run consumes 1 of the backend's 5/hour password-reset
   requests — >5 runs/hour starves `release-flows`. **CORRECTION (found by the
   coach_console worker): the window is Redis-backed, not in-memory — a backend
   restart does NOT clear it. `redis-cli -p <lane redis port> FLUSHALL` on the
   lane's OWN Redis does (never dev's :6379).**

**Real app defects found, NOT fixed (operator/backlog list):** `/solutions`
+ `/solutions/[slug]` amber `#c4720a` at 3.65:1; `/platform` `#6b7a96` at
4.33:1; dark-scope `bg-accent`+white at 3.97:1 (needs a design decision);
pipelines admin badge `#2c7a52` on `#e6efea` at **4.46:1** (0.04 under AA,
renders only with accumulated data — found by the orchestrator's dirty-lane
runs); dead LMS-era `SolutionSlug` content in `content.ts` linking three
404 routes. Validator hardening residual for `sweep_fe_test_backfill`: give
the denial-only role tests a positive anchor so a stale storageState cannot
satisfy them vacuously.

### ✅ `diff_coverage_and_lint_gate` — LANDED

**backend `ee56126` · web `34b47b7`**, stacked on the landed tips. Evidence,
all orchestrator-observed: Gate 1 693/0 · Gate 3 green through the NEW
blocking semantics · Gate 4 89/89 on seeded lane 1 · Gate 5 clean incl. 4
declared manifest additions · `pom.xml` diff = 0 lines (JaCoCo floor
constraint mechanically verified) · orchestrator ran his own falsification
(scratch file → 0/3 → exit 1). Gate-integrity validator PASS after reading
both scripts and the ESLint/vitest internals they depend on.

**What shipped:** ESLint 9 native bulk-suppressions ratchet (255 violations
baselined by file+rule+count, blocks new AND flags stale, 5 rules warn→error,
`--max-warnings 0`) · dependency-free diff-coverage per repo
(`web/scripts/diff-coverage.mjs`, `backend/tools/DiffCoverage.java` via JDK
single-file launcher) at 70% on changed lines · CI wiring (UNVERIFIED
locally, fails closed).

**Gate commands for every future ticket (wire verbatim):**
```
cd backend && ./mvnw -q test && java tools/DiffCoverage.java --base <prev-ticket-sha>
cd web     && pnpm coverage:diff --base <prev-ticket-sha>
cd web     && pnpm lint
```

**Standing rules from the validator (binding on the orchestrator):**
1. A coverage-gate green claim is accepted ONLY with the printed
   `threshold 70%` header and `TOTAL x/y` lines quoted (threshold is
   env-overridable; the printout is the falsifiable part).
2. Per-ticket runs pass `--base <previous ticket's sha>` — the default base
   is the run's branch point, and stacked commits dilute (200 covered lines
   + 50 uncovered = 72% PASS).
3. The flag is read NARROWLY: `.tsx` (479 files, 84% of measured web churn)
   is excluded by construction (node-env vitest cannot render) and stays on
   Gate 4 evidence. No ticket may cite this gate as component evidence.

**Recorded findings/residuals:**
- **Born red on inherited work:** error_tracking's changed lines measure
  53.7% (be) / 28.6% (web) vs the default base — the operator's eventual PR
  to staging fails the new CI step until that debt is covered (worst:
  `ErrorEventController` 5/24, `instrumentation.ts` 0/6). Deliberately not
  backfilled by this radius-S ticket.
- `sweep_fe_test_backfill`'s declared verification mechanism ("verified by
  the diff-coverage gate") does not exist for `.tsx` yet — that sweep must
  first add a jsdom/browser coverage project.
- `--min` with a missing value silently passes in the web script (NaN
  compare) — one-line fix, queued for the next platform-zone ticket.
- Unused-vars can be silenced by `_`-renaming rather than deletion
  (conventional escape hatch, worth knowing).

### ✅ `scope_manifest_gate` — LANDED (Phase 0 complete)

**web `071f6ca`** (backend: no change), one fix cycle after a CONDITIONAL
PASS. Evidence, orchestrator-observed: Gate 3 green through blocking lint
(225 tests, incl. the gate's own hermetic CLI test) · Gate 5 via the gate's
own self-check · empty-diff guard verified exit 2 · landed parents intact
after the amend (the "amend of a foreign commit" security warning was a
false alarm — the agent amended its own unlanded ticket commit on my
instruction, per the one-commit-per-ticket mechanic).

**Gate 5 is now mechanized. The command, wired verbatim for every future
ticket** (run in `web/` of any checkout; `--repo` may point at either repo):
```
pnpm scope:check --repo <checkout> --base <stack point> --glob '<manifest glob>' [--glob ...]
```
Exit 0 in-scope · 1 out-of-scope (names each path and whether a
`never_touch` clause fired — those veto even a `**` manifest) · 2 usage
error (incl. empty diff = wrong base). `always_in_scope` and `never_touch`
are hardcoded from the policy; `agent-policy.yml` itself is vetoed
unconditionally.

**Recorded ceilings** (in the script header): manifest globs are
self-declared at gate time (binding to the base-commit run report is named
future work); the test-mirroring qualifier is not mechanised; zone→path
ownership is unenforced (no map exists in the policy).

### `error_tracking` — cycle 1 history (superseded)

Orchestrator gate observations on `2fdd658`/`26f75c8`:

| Gate | Orchestrator-observed result |
|---|---|
| 1 backend | ✅ 690 tests, 0 failures, ArchUnit clean |
| 2 contract | ✅ export clean; regen delta = springdoc's known non-deterministic `Page*` property reshuffle only; typecheck clean |
| 3 web | ✅ identical to baseline (0 lint errors / 265 warnings, typecheck clean, 217/217 unit) |
| 4 e2e | 🔶 red, **pre-existing** — observed 36 passed / 15 failed / 34 cascade-skipped; failure set = the known auth.setup timeout cascade + `/solutions/*` 404s + a11y + catalog/nav, same shape as the worker's stashed-diff baseline. Zero rows written to `error_events` during the run corroborates none are crashes. Strict green is `e2e_local_green`'s job. |
| 5 scope | ✅ every path in-manifest; probes dev-gated |

Validators (context: diff + spec + policy only): tenant-scoping **PASS** ·
policy-compliance **PASS** · **RBAC/3-layer VETO** (4 findings: Server-Action
confused deputy exposing the proxy secret; single-layer unmetered ingest;
untruncated `requestPath` + no `@Size`; dev probe's false javadoc premise +
missing method-level rule). Advisory lenses: 25 findings, convergent on
unbounded ingest, missing retention, and `request_id`/`digest` namespace mixing.

Veto findings + convergent advisory items routed back to the implementer as a
fix task (amend in place, one commit per repo). Re-gate from Gate 1 plus
re-validation on the amended diff. V145 may be amended in the fix (never left
its branch); lane 1 reset clears the Flyway checksum.

**Advisory findings deliberately NOT fixed this cycle** (recorded for the
operator): `Slice` vs `Page` on the query endpoint; `stackTrace` in the list
DTO (page-size payload); explicit 405/415/ClientAbort handlers to keep bot
noise out of the store; design-token values in `global-error.tsx` inline
styles. Also: `defaults.new_ui_copy: ENGLISH_VIA_NEXT_INTL` is currently
unsatisfiable — next-intl is not installed in web at all (policy-vs-reality
gap, affects every UI ticket until someone lands it).

### ✅ `gdpr_export_delete` — LANDED (4 cycles)

**backend `aa7b7f8` · web `eee9cd1`** (one commit per repo). Final evidence,
orchestrator-observed: Gate 1 708/0 BUILD SUCCESS · Gate 2 contract clean
(204 + `currentPassword` published; cycle-4 delta was DTO-free) · Gate 3 =
baseline · Gate 4 59/14/12 pre-existing-only · Gate 5 12 paths = widened
manifest, frozen store byte-identical, V146 (15 CREATE INDEX) the only
migration. Cycle-4 deletion grep-verified by the orchestrator; worker
mutation-tested the pins (reinstating the vetoed arm flips 3 tests red).

What shipped: `GET /api/gdpr/me/export` (33 id-keyed sections, shape-based
credential strip, Art. 15(4) identity-strip on account_activity, org section
narrowed to id+name, 5/hr per-user window) · `DELETE /api/gdpr/me`
(currentPassword re-auth when hash exists / email-confirm for SSO-only,
5-per-15-min attempt throttle, 400-not-401 by BFF-refresh design) · shared
id-keyed eraser in `common/gdpr` called by BOTH self-service and admin
permanent delete (fixing the latent admin-path CHECK-constraint abort) ·
per-class erasure: hard-delete own records, anonymise certificates
("Deleted user", verify-by-number preserved per V85) + reviews, detach
third-party replies, scrub only rows *about* the subject in audit_logs,
retain org content with attribution nulled · profile privacy section +
honest public privacy-page copy.

**Follow-ups recorded for the operator/backlog:**
- Email-keyed personal data (public-flow submissions/survey responses,
  invitations, audit-blob addresses) is deliberately OUT of self-service
  reach until mailbox proof exists — `PasswordResetService`'s token
  round-trip is the named pattern; until then it is a manual, human-verified
  request (privacy page routes it to email).
- `ASSESSMENT_ASSIGNED` audit rows with dangling submission entity_ids keep
  `memberName` — upstream fix belongs to the assessment zone: write
  `memberId` into that row's details.
- V146 ships `idx_invitations_lower_email` + a comment describing a removed
  consumer — dead index, immutable migration; correct in a later migration.
- Privacy-page legal wording needs human sign-off (flagged in-session).
- Per-instance in-memory rate windows (documented `ponytail:` ceilings).
- `error_events` (from error_tracking) is a PII-adjacent sink the erase flow
  does not reach — no user key on rows; cross-ticket, recorded.

**Cycle history:** c1 three vetoes (actor-arm ×3 lenses, org-row exposure,
no re-auth, comments cascade) + contract HIGHs (ai_call_logs survival,
divergent admin path) → c2 fixed those, three NEW vetoes (unverified-email
squatter attack, admin blast radius — both traceable to the orchestrator's
C4 "extend" call) → c3 removed email-keyed arms, three unanimous vetoes on
the one retained value-strip predicate → c4 two-line deletion, mechanical
verification, landed. Monotone convergence; nothing re-litigated.

### `gdpr_export_delete` — cycle 1 history (superseded)

Worker submission: backend `ffa5f4f`, web `fe0e86b`. **No migration — V146
released back to the pool** (next allocation starts at V145-after-amend / V146).

| Gate | Orchestrator-observed result |
|---|---|
| 1 backend | ✅ 692 tests, 0 failures, ArchUnit clean (includes 7 new `GdprIntegrationTest`) |
| 2 contract | ✅ both `/api/gdpr/me*` endpoints in spec; regen delta = springdoc reshuffle only; typecheck clean |
| 3 web | ✅ identical to baseline (0/265 lint, typecheck, 217/217) |
| 4 e2e | 🔶 red, **pre-existing** — observed 59 passed / 14 failed / 12 skipped; failure set = the same known list (auth.setup cascade, `/solutions/*` 404s, a11y, catalog, nav); none touch `/app/profile` |
| 5 scope | ✅ 8 paths, all in-manifest; serialising paths untouched; no migration taken |

Six validators running (fresh context: diff + spec + policy). Two erasure-design
judgment calls surfaced to the operator in-session (audit `details_json` scrub
on id-matched rows; `course.instructor_name/_title/_bio` retained as editorial
content). Also reported by the worker, pre-existing: `survey_responses` FK
`SET NULL` collides with its CHECK constraints — **the existing admin
hard-delete path would abort on any user with survey responses**; latent prod
bug, not introduced here.

**Cycle 1 verdict:** three vetoes (policy, RBAC, tenant — all converging on the
audit-scrub `actor_id` arm anonymising third parties; RBAC added org-row
over-exposure + missing re-auth; tenant added the exercise_comments cascade
destroying third parties' replies). Contract lens added two HIGHs
(`ai_call_logs` survives erasure with verbatim answers; the admin permanent-
delete path is a second divergent erasure that aborts on survey responses).
Consolidated fix task dispatched; V146 re-allocated for FK indexes.

**Cycle 2 verdict (amended commit `b0483ad`/`55150a5`):** all cycle-1 findings
verified resolved by all lenses; gates 1,2,3,5 re-observed green, Gate 4
re-observed 59/14/12 pre-existing-only. **Three vetoes again, convergent on two
NEW defects, both introduced by cycle 2's email-keyed extension — which
followed the ORCHESTRATOR's C4 "extend" instruction; the error was the
orchestrator's, caught by the panel:**
1. `users.email` is unverified (permitAll register, instant ACTIVE) yet the
   email-keyed export/erase arms treat it as data-subject proof. Squatter
   attack: register any unclaimed address → export/destroy that person's
   public-link submissions, survey responses, invitations, platform-wide.
2. The unified eraser gave the ADMIN path the subject's platform-wide reach —
   org A's admin deleting a member destroys org B's records.
Cycle 3 dispatched: email-keyed arms removed (C4 reverts to "document", with
verified-email named as the unlock), `subjectInitiated` gate on the one
remaining value-keyed scrub arm, delete-attempt throttle (bcrypt oracle),
cross-tenant regression test (the fixture gap that let both findings live),
dual-SSO+password dialog dead end, privacy-page wording aligned pending
operator sign-off.

**Cycle 3 verdict (`c09f147`/`eee9cd1`):** gates 1,2,3,5 re-observed green
(708/0), Gate 4 re-observed 59/14/12 pre-existing-only; squatter attack
demonstrated FAILING end-to-end; cross-tenant tests green. All cycle-2
findings resolved EXCEPT one: **three unanimous vetoes on a single line** —
the `subjectInitiated`-gated audit email value-strip. Rulings: policy =
platform-wide form beyond the flow (actor-anchored or deletion accepted);
RBAC = pure deletion (any email-value predicate contradicts the module's own
boundary; also caught the cross-tenant test asserting the opposite of its
name); tenant = deletion (attacker-steerable predicate, two-call reach,
silent cross-tenant audit mutation). Cycle 4 dispatched: delete the arm and
the now-purposeless `subjectInitiated` plumbing, invert/pin the tests, fix
the MemberService javadoc claim, one extra unforgeable-arm fixture.
Convergence is monotone: cycle 1 hit design classes, cycle 2 two statement
classes, cycle 3 one predicate, cycle 4 is a two-line deletion.

### ✅ `founder_dashboard` — LANDED (2 cycles)

**web `fcae0f1`** (one amended commit off the integration tip; backend zero-diff,
verified). Evidence, all orchestrator-observed on the amended state: Gate 3
lint 0 warnings (blocking) / typecheck clean / **259/259** unit (34 in the new
model test) · coverage `threshold 70%` → `TOTAL 79/79 changed lines = 100.0%
PASS` · Gate 5 scope 5/5 paths in-manifest, exit 0 · Gate 4: 93 tests, two
consecutive seeded-lane runs each **92/1** where the single failure was a
DIFFERENT one of the two documented pre-existing latent a11y defects
(fingerprint-verified from the error context each time: pipelines `#2c7a52` @
4.46:1 16 nodes; ai-config button-name/label/contrast) on admin routes this
diff never touches — every diff-owned test and all role-boundary evidence green
in both runs. Standard + retirement plan: decision log → *"Gate 4 standard
while two documented latent a11y defects race the scanner"*.

Validators: RBAC (veto, widened with self-data-boundary) **PASS** — session-
first ordering, per-request cache scoping, server-derived ids only, no e2e
evidence weakened · policy **PASS** — all four acceptance criteria verified in
the rendered tree, every empty state names an action, no LMS vocabulary drift,
one-commit discipline · advisory: 9 findings, fix cycle ran, re-verified
**FIX-CYCLE: SATISFIED** (8/9 fixed incl. both mandatory: the false
"completed every task" headline for drip-locked members, and whole-app crash
on one failed fetch — now per-section degradation with named next actions;
9th = the run-level next-intl deferral).

What shipped: member home is a server-rendered dashboard — `NextActionCard`
(actionable-first priority; readOnly/done/locked/unavailable states can never
claim false completion), modules with per-module completion + deep links to
the first open task, pillar snapshot with sr-only maturity tier; level-1
session ∥ assessments, level-2 journey ∥ results (depth 2); admins keep their
hub with the spotlight cache seeded, MANAGER folded into the member home.

**Integration seam (for the spine's landing):** `page.tsx` compares
`role === "MANAGER"`; when coach_console's role-union change integrates this
becomes a TS2367 typecheck error — resolve at integration by dropping the arm.

**Residuals (operator/backlog):** the two latent a11y defects + scan race →
`gate4_determinism` micro-fix scheduled immediately after Phase 1 lands; seed
cohort has "Untitled module/task" rows; `maturityIndication` null on seeded
latest-evaluated results; "1 of 1 tasks" not singularized; `unavailable`
action's "Try again" links `/app` to itself (works via RSC refetch, refresh
affordance would be cleaner); no `(app)/error.tsx` (owned by
`sweep_route_boundaries`).

### ✅ `coach_console` — LANDED (3 cycles: full → veto fix → M9)

**backend `b4b16ba` · web `90dfccf`** (one amended commit per repo, off the
integration fork points). Final evidence, all orchestrator-observed on the
final state: Gate 1 **735/0** (surefire XML aggregate) + DiffCoverage
`threshold 70%` → `TOTAL 199/213 = 93.4% PASS` · Gate 2 contract pipeline
clean, `CoachFounderSummary` email-free in the generated schema, role-union
pins added · Gate 3 lint 0 / typecheck 0 / **236/236** + `TOTAL 14/14 =
100.0% PASS` · Gate 4 **105/105** on freshly seeded lane 1 (strict green;
suite grew 89→105 with the coach specs) · Gate 5 backend 26 paths / web 28
paths all in-scope (incl. the logged one-file M8 widening) · frozen store
byte-identical · V147 single migration, expand-only verified against V75
originals.

Validator history: cycle-1 panel = tenant PASS · policy PASS · **RBAC VETO ×2**
(other-cohort leak via shared founders; founder email beyond `coach_sees`) ·
contract SERIOUS (terminal MANAGER invitations hydration-500 the admin
invitations list) · perf MINOR · UX SERIOUS. Fix cycle (M1–M9 + R9–R16) →
re-verification: **RBAC VETO CLEARED** · tenant PASS (full re-trace of the
rewritten predicates) · UX FIX-CYCLE SATISFIED · contract MINOR, all fixed
(enum-hydration re-attack found no residual path: JWT role claim write-only,
Jackson 4xx-safe, audit blobs never re-read).

What shipped: `coaching` slice (V147 `coach_assignments`, cohort-XOR-member
grain, org-denormalised; raw-SQL reads per the PersonalDataRepository
precedent; single-sourced visibility predicate in `common/coachaccess` with
`status='ACTIVE'`); **grain-scoped visibility** (ruling logged: cohort grant →
granted-cohort data only; direct grant → the founder's full journey);
review/comment via the EXISTING exercise pipeline (5 endpoints admit in-org
COACH behind a fail-closed one-SQL gate); MANAGER fully removed (holders AND
all invitation rows → MEMBER; enum constant deleted both sides); coach console
(`/app/coach` roster/founder detail/review with PageHero wayfinding), sub-org
Coaches tab (assign/unassign both grains, error-honest empty states), COACH
nav section; coach-facing payloads carry no founder email (roster DTO and
review payload both).

**Residuals (operator/backlog):**
- `coach_sees` lists `reflections`; no reflections surface exists — nothing in
  the product stores one (no table). Logged deviation; needs a product call.
- Composite org-congruence FKs on coach_assignments (defense-in-depth belt) —
  named as a possible follow-up migration; read predicates already re-check.
- Roster unbounded (ponytail ceiling comment in code; paginate at scale).
- Grant-reactivation on a member's round-trip sub-org move (grants are never
  revoked on move) — policy question.
- `CoachAccess.coachSees` now has no production caller (dead public method).
- Migration test inserts a live MANAGER row in the shared schema — fragile if
  surefire parallelism is ever enabled.
- UX one-liners: "an admin" → "program admin" at founder-detail:145; 404
  back-label says "My Founders" while the href follows `?founder=`.
- Review-screen coach copy: "1 of 1 tasks" pluralisation (inherited).

### ✅ `quantitative_benchmarking` — LANDED (3 cycles)

**backend `614064f` · web `08d0732`** (one amended commit per repo, off the
post-spine integration tips — cherry-picks clean by construction). Final
evidence, all orchestrator-observed: Gate 1 **749/0** + DiffCoverage `TOTAL
63/63 = 100.0% PASS` · Gate 2 pipeline clean (p50 removed end-to-end; 3 DTO
pins) · Gate 3 lint 0 / tc 0 / **277/277** + `TOTAL 10/11 = 90.9% PASS` ·
Gate 4 **112/112** on freshly seeded lane 1 · Gate 5 8+9 paths in-scope · no
migration taken (V148 released back to the pool) · frozen store untouched.

Validator history: cycle 1 = policy PASS · advisory MINOR · **CONVERGENT
DOUBLE VETO (tenant + RBAC, independently identical)**: the platform aggregate
was differencable against the caller's own org — at own-29/platform-30 the
arithmetic recovers a single foreign founder's pillar score. Cycle 2
(complement floor keyed to the path org) → **both lenses independently found
the identical surviving attack one hierarchy hop up** (a parent admin lawfully
knows all sub-org scores; V136 makes sub-orgs the default topology; a sibling
fills the complement). Cycle 3 (family complement floor —
`COALESCE(parent,id)` root-normalized NOT-IN set, one-level hierarchy verified
service-enforced) → **both vetoes CLEARED**: the tenant lens proved the
general theorem (an admitted caller knows zero founders of any rendered
complement → single-subject reconstruction arithmetically impossible), RBAC
verified the root-path IT leg is the discriminating mutation-killer.

What shipped: `GET /api/organizations/{orgId}/benchmarks` (ORG_ADMIN
hierarchy-aware + SUPER_ADMIN; COACH/INSTRUCTOR/MEMBER 403 — IT + e2e via API
status) · live SQL aggregation in `insights/BenchmarkReadRepository`
(DISTINCT-founder grain, latest evaluated per founder) · three segments:
cohort ⊆ org ⊆ platform, per-pillar mean + p25–p75 on a 0–100 track ·
min-sample 30 enforced IN SQL (CASE for own segments; double HAVING for
platform: total ≥ 30 AND ≥ 30 outside the caller's org FAMILY) ·
whole-row suppression → count-free "insufficient data" states, every one
naming a next action · published-pipeline gate (DRAFT ≡ nonexistent 404) ·
benchmark panel on the org insights tab (shared-Skeleton loading inside the
always-mounted region, keepPreviousData with aria-busy/dim staleness
treatment, sr-only percentile spans, honest picker error/empty states).

**Residuals (operator/backlog):** temporal differencing on the exact live n
(watch it tick; bucket n or snapshot cadence if ever wanted — inherent to
live aggregates, not floor-fixable) · k-root collusion (unrelated tenants
jointly differencing) outside the family model · single-org-pipeline
out-of-band attributability (product call) · sub-org admins over-suppressed
by sibling exclusion (deliberate safe direction, documented) · platform
recompute per cohort switch (ceiling comment; split the query key if it ever
registers) · `OrgAccessGuard` static hierarchy port (pre-existing test-run
landmine, cleanup-ticket candidate) · one uncovered changed web line
(`reporting-keys.ts`).

### ✅ `authoring_honesty` — LANDED (1 cycle)

**backend `c9cf583` · web `03fb4cd`**. Evidence, orchestrator-observed: Gate 1
**755/0** + `TOTAL 16/16 = 100.0% PASS` · Gate 2 no contract delta (deprecation
changes no schema; union correctly keeps all 12 values for legacy rows) · Gate 3
lint 0 / tc 0 / **280/280** + `TOTAL 3/3 = 100.0% PASS` · Gate 5 3+3 paths
in-scope · Gate 4 under the extended attributed standard (decision log): 109
non-benchmarking tests green ×2, benchmarking spec **9/9 in isolation ×3** on
this branch; sole full-suite failure = the documented select-commit race
(fingerprint: option `[selected]` + no-pipeline prompt), zero causal path from
a catalog-only diff. Validator (narrowed fused lens — policy+contract+test-
integrity, logged): **PASS** with its own independent player-dispatch
enumeration and a one-choke-point write-path bypass hunt.

What shipped: reality-corrected dead set **{SCORM, WEBPAGE, DOCUMENT, IMAGE}**
retired from authoring (ARTICLE renders — the `known_issues` line is wrong
twice over; ruling logged); constants DEPRECATED never deleted (seeded SCORM
rows exist — the enum-hydration trap avoided by design, pinned by a test that
parses the live CHECK constraint and fails if any constant is deleted);
server-side rejection on create AND update through the single choke point;
unknown type 500→400; editor shows legacy types as disabled "(retired)" with
Save disabled.

**Residuals:** player's data-conditional branches (VIDEO w/o URL → placeholder)
→ `courses_qa_and_flag` · web's 8-entry authorable list duplicates the
backend's (both test-pinned, drift risk noted) · `known_issues` line 522
factually wrong — **operator should correct it at source** · dead `"asset"`
MediaKind member (out-of-manifest, left).

### ✅ `gate4_determinism` — LANDED (2 cycles; scan-integrity veto cleared)

**web `7111bad`** (6 files; backend zero-diff, verified). Retires the a11y
attributed exception. Evidence, orchestrator-observed: Gate 3 lint 0 /
typecheck 0 / **280/280** · Gate 5 6 paths in-scope (4 manifest + 3 logged
widenings, badge.tsx untouched by design) · Gate 4 **112/112 twice
consecutively** on a freshly reset lane, plus the worker's own 3 consecutive
greens = **5 clean full-suite runs**.

Cycle 1 → **scan-integrity VETO**: the skeleton-drain settle gate was vacuous
on 2 of 7 scanned routes (surveys and member-types hand-rolled their loading
states as `animate-pulse` divs instead of the shared `Skeleton`, so the gate
saw zero skeletons and passed instantly) plus the spotlight island rendering
`null` while loading. Cycle 2 converted all three to the shared component
(verified: **zero `animate-pulse` remaining**, Skeleton present in each).

What shipped: root-caused the contrast defect to the **token** rather than the
badge (`--success #2c7a52 → #276e49`, light mode only; every consumer pairing
recomputed ≥4.6:1 — 10 pairings tabulated), the whole ai-config label defect
class fixed via the file's own `useId` idiom, and the a11y scan now waits for
skeleton drain (5 consecutive rAF polls, 30s ceiling that FAILS loudly) instead
of racing hydration off `h1` visibility.

**The a11y attributed Gate-4 exception is retired from this commit forward** —
together with `bench_spec_hardening`, strict full-suite green is the bar again.

**Residuals:** `insight-text.ts:53` and `celebration.tsx:15` hardcode the old
`#2c7a52` as decorative fills (now ~10% off token, zero contrast impact,
out-of-manifest); the documented `button-name` fingerprint could not be
reproduced under the honest scan (fixed by an earlier landed ticket).

### ✅ `bench_spec_hardening` — LANDED (orchestrator micro-fix, 1 cycle)

**web `9ddf122`** (1 file, +27/−3; backend untouched). Retires the logged
select-commit-race Gate-4 exception: the spec now verifies the pipeline select
COMMITTED (the Benchmarks region mounting) with a bounded 3×5s retry, no
sleeps, canonical failure preserved; the moved `expect` is byte-identical
(diff-verified). Evidence: worker 3× spec-alone + full 112/112 (its hardened
test green in 8/8 runs incl. ones where the dev server was dying); my re-run
lint 0 / tc 0 / scope 1-1 / full suite 110/1 with the sole failure = the
OTHER documented fingerprint (ai-config a11y race — retired by the in-flight
gate4_determinism ticket), hardened spec PASSED. **The select-commit-race
attributed exception is retired from this commit forward.**
Operational note from the run: 4 concurrent lane dev servers ≈ the box's
ceiling (Turbopack native crashes, 3.7GB free RAM) — stagger e2e where
possible; `rm -rf .next` cures wedged dev servers.

### ✅ `courses_qa_and_flag` — LANDED (3 cycles; convergent retry veto cleared)

**web `af04f1c` → integration `9db36f5`** (backend zero-diff, verified). Flag NOT
flipped — operator deployment decision, §7 item 0c. Evidence, orchestrator-observed
on the final tree: Gate 3 lint 0 / tc 0 / **287/287** + coverage **14/14 = 100%**
(header quoted) · Gate 5 8 paths in 5 globs PASS · Gate 4 **117/117 twice
consecutively, flag ON** on freshly-seeded lane 1, with the 117-test list verified
to contain the 4 player tests · integration combination lint 0 / tc 0 / 287/287,
CR-bytes 0. Panel: test-integrity VETO (unbounded query retry — found convergently
by both lenses; fixed via extracted pure `shouldRetryQuizLoad` + discriminating
pin) and UX blocking items (zero-question quiz Submit; mark-complete under empty
states) — all closed, both lenses PASS on re-verify. What shipped: honest
per-type empty states unified through `lessonSurface` (PlaceholderCard deleted for
shared `EmptyState`), two real quiz-taker defect fixes, `walkCurriculum` player
coverage. Residuals → `ux_p0` list in the decision log.

### Phase 2 wave 1 — intake manifests (declared 2026-07-26, before any code)

All three run off integration tips `31e4bdf`/`3a891bd`. `contract-check.ts` +
`api-schema.d.ts` are integration-resolved append files this wave (decision
log). Named lib files only — never `lib/**`.

**`authoring_honesty`** (catalog, S, lane 1):
```
backend/src/main/java/com/bvisionry/catalog/**
backend/src/main/java/com/bvisionry/common/enums/ContentType.java   (only if data reality allows — see brief)
web/src/app/(app)/app/admin/courses/**
web/src/lib/generated/api-schema.d.ts        (integration-resolved)
web/src/lib/contract-check.ts                (integration-resolved append)
web/e2e/catalog.spec.ts
```

**`roi_reporting`** (insights, M, lane 3):
```
backend/src/main/java/com/bvisionry/insights/**
web/src/app/(app)/app/admin/insights/**
web/src/app/(app)/app/admin/analytics/**
web/src/lib/roi-report-types.ts  web/src/lib/roi-report-api.ts  web/src/lib/roi-report-keys.ts   (new)
web/src/lib/generated/api-schema.d.ts        (integration-resolved)
web/src/lib/contract-check.ts                (integration-resolved append)
web/e2e/roi-report.spec.ts                   (new)
```

**`announcements`** (communication, M, lane 4):
```
backend/src/main/java/com/bvisionry/communication/**    (new package)
backend/src/main/java/com/bvisionry/notification/**     (new type + preference wiring)
backend/src/main/resources/db/migration/V148__*.sql
web/src/app/(app)/app/coach/**                          (coach broadcast to assigned cohorts)
web/src/app/(app)/app/admin/sub-organizations/**        (org-admin cohort surfaces)
web/src/components/app/**                               (notification bell rendering of the new type)
web/src/lib/announcements-types.ts  web/src/lib/announcements-api.ts  web/src/lib/announcements-keys.ts   (new)
web/src/lib/generated/api-schema.d.ts        (integration-resolved)
web/src/lib/contract-check.ts                (integration-resolved append)
web/e2e/announcements.spec.ts                (new)
```
Disjoint by construction: roi owns `admin/insights/**`+`admin/analytics/**`;
announcements owns `coach/**`+`admin/sub-organizations/**`+`components/app/**`;
authoring owns `admin/courses/**`+`catalog/**`. gate4 (live) owns
`admin/ai-config/**`, `ui/badge.tsx`, `globals.css`, `a11y-app.spec.ts` + its
3 widened loading-state files (incl. `app/_components/workspace-spotlight.tsx`
— announcements' `components/app/**` claim does NOT extend there).

**`courses_qa_and_flag`** — declared at intake 2026-07-26 (backlog has no
globs). Zone catalog, lane 1:
```
backend/src/main/java/com/bvisionry/catalog/**
web/src/app/(app)/app/courses/**
web/src/app/(app)/app/admin/courses/**
web/src/lib/generated/api-schema.d.ts        (integration-resolved)
web/src/lib/contract-check.ts                (integration-resolved append)
web/e2e/catalog.spec.ts
```
The catalog feature flag's own location is declared-by-ask once the worker
finds it (unknown at intake). No migration allocated.

### Phase 2 wave 2 — intake manifests (declared 2026-07-26, before any code)

All three fork the wave-1 integration tips `e1b6647`/`fc9acd0`. `contract-check.ts`
+ `api-schema.d.ts` remain integration-resolved append files. **V149 is
conditionally allocated to `inactivity_and_proactive_nudges`** (only if the
per-org N-days config needs storage; nothing else may take it). No other wave-2
ticket may add a migration.

**`competency_matrix`** (webapp, S, lane 2):
```
backend/src/main/java/com/bvisionry/insights/**       (ONLY if a new read endpoint is genuinely needed — raw-SQL read repo per BenchmarkReadRepository precedent, NO migration; prefer composing existing endpoints first, per the founder_dashboard rule)
web/src/app/(app)/app/_components/**                  (founder-facing surface integration)
web/src/app/(app)/app/admin/insights/**               (cohort matrix panel — claim released by roi landing)
web/src/components/app/competency/**                  (new)
web/src/lib/competency-types.ts  web/src/lib/competency-api.ts  web/src/lib/competency-keys.ts   (new named files)
web/src/lib/generated/api-schema.d.ts                 (integration-resolved)
web/src/lib/contract-check.ts                         (integration-resolved append)
web/e2e/competency-matrix.spec.ts                     (new)
```
Policy constraints binding: `competency_matrix_axes: [pillar, maturity_band]`,
`competency_movement_window: ALL_ASSESSMENTS` (full history, not last-two).

**`inactivity_and_proactive_nudges`** (notification, S, lane 4):
```
backend/src/main/java/com/bvisionry/notification/**   (the trigger job + wiring; infra exists)
backend/src/main/java/com/bvisionry/organization/**   (per-org N-days config, if it lands there)
backend/src/main/resources/db/migration/V149__*.sql   (conditionally allocated — see above)
web/src/app/(app)/app/admin/sub-organizations/**      (org-admin config surface, if UI is needed — claim released by announcements landing)
web/e2e/nudges.spec.ts                                (new)
web/src/lib/generated/api-schema.d.ts                 (integration-resolved)
web/src/lib/contract-check.ts                         (integration-resolved append)
```
Policy binding: `nudge_channels: RESPECT_EXISTING_PREFERENCES` — the nudge goes
through the existing opt-out-filtered dispatch, never a new channel. A surface
outside these globs = ask, never improvise.

**`sweep_preauthorize_audit`** (readonly, S, no lane): read-only fan-out; ZERO
code changes — the deliverable is a findings report to the orchestrator, who
records it in the decision log. Includes verifying the recorded advisory that
the insights surfaces lack a dedicated route-layer matcher.

### Intake scope manifests

`scope.undeclared: DECLARE_AT_INTAKE` — a ticket with no `scope:` globs in the
backlog gets its manifest written here **before any code**, so Gate 5 has
something to check and the operator can see what was claimed.

Every manifest below is implicitly extended by `scope.always_in_scope` **minus**
`agent-decisions.md` and `agent-run-report.md`, which the orchestrator writes
exclusively (see the decision log). Workers do not touch those two files.

**`error_tracking`** — declared in the backlog, reproduced here for Gate 5:
```
backend/src/main/**            backend/pom.xml
web/src/**                     web/package.json                 web/next.config.ts
+ backend/src/main/resources/db/migration/V145__*.sql   (its allocated number, only)
```
Holds the serialising paths `backend/pom.xml`, `web/package.json`,
`web/next.config.ts` — so no other ticket may touch them until it lands.

**`gdpr_export_delete`** — declared at intake (backlog has no globs):
```
backend/src/main/java/com/bvisionry/gdpr/**        (new feature package)
backend/src/main/java/com/bvisionry/auth/**
backend/src/main/resources/db/migration/V146__*.sql (its allocated number, only)
web/src/app/(app)/app/profile/**
web/src/lib/generated/api-schema.d.ts             (regenerated by `pnpm gen:api`, never hand-edited)
web/messages/**                                    (next-intl strings — defaults.new_ui_copy)
```
Excludes every `scope.serialising_paths` entry (held by `error_tracking` in
parallel) and everything under `hard_constraints.never_touch`.

**`coach_console`** — declared at intake 2026-07-26 (backlog has no globs).
Spine ticket; zone `coaching`; holds the migration glob (V147 exactly):
```
backend/src/main/java/com/bvisionry/coaching/**       (new feature package)
backend/src/main/java/com/bvisionry/common/**         (UserRole enum; shared coach-access kernel if the design needs one)
backend/src/main/java/com/bvisionry/auth/**           (role wiring)
backend/src/main/java/com/bvisionry/organization/**   (MANAGER references; org-admin coach assignment surface)
backend/src/main/java/com/bvisionry/config/**         (security wiring)
backend/src/main/java/com/bvisionry/exercise/**       (COACH authorization on the existing submission-review surface, if the design lands there)
backend/src/main/resources/db/migration/V147__*.sql   (its allocated number, only)
web/src/app/(app)/app/coach/**                        (new console routes)
web/src/app/(app)/app/admin/**                        (assignment UI; MANAGER references)
web/src/components/app/**                             (sidebar role gating)
web/src/components/admin/**
web/src/lib/**                                        (roles.ts, auth.ts, admin-types.ts, generated api-schema.d.ts, contract pins)
web/e2e/coach-console.spec.ts                         (new)
web/e2e/console-surfaces.spec.ts
web/e2e/nav.spec.ts
web/e2e/auth.setup.ts
web/e2e/_helpers.ts
```

**`founder_dashboard`** — declared at intake 2026-07-26 (backlog has no globs).
Zone `webapp`; frontend-only by design (the roadmap: "all data already exists" —
compose existing endpoints; a new backend endpoint means asking the
orchestrator first):
```
web/src/app/(app)/app/page.tsx                        (the home — link grid becomes a dashboard)
web/src/app/(app)/app/_components/**
web/src/components/app/dashboard/**                   (new, if shared components are wanted)
web/e2e/founder-dashboard.spec.ts                     (new)
web/e2e/smoke.spec.ts
web/e2e/a11y-app.spec.ts
```

**e2e file partition (conflict avoidance, binding):** coach_console owns
`console-surfaces.spec.ts`, `nav.spec.ts`, `auth.setup.ts`, `_helpers.ts`;
founder_dashboard owns `smoke.spec.ts`, `a11y-app.spec.ts`. A worker needing
the other lane's file asks the orchestrator (logged widening), never edits it.
`web/src/lib/**` belongs to coach_console alone this phase.

**`quantitative_benchmarking`** — declared at intake 2026-07-26 (backlog has
no globs). Zone insights; V148 conditionally allocated:
```
backend/src/main/java/com/bvisionry/insights/**
backend/src/main/resources/db/migration/V148__*.sql   (only if needed)
web/src/app/(app)/app/admin/insights/**
web/src/app/(app)/app/admin/analytics/**
web/src/lib/**                                        (insights types/api/keys; generated schema)
web/e2e/benchmarking.spec.ts                          (new)
```

**`gate4_determinism`** — orchestrator-scheduled micro-fix (decision log:
*"Gate 4 standard while two documented latent a11y defects race the scanner"*).
Zone webapp:
```
web/src/components/ui/badge.tsx
web/src/app/globals.css                               (the --success token, if that is where the fix lands)
web/src/app/(app)/app/admin/ai-config/**
web/e2e/a11y-app.spec.ts
```
Partition: disjoint by construction (benchmarking's admin globs exclude
ai-config; a11y-app.spec.ts is gate4's alone; lib/** is benchmarking's alone).

### Pre-change baseline — measured by the orchestrator, not claimed

Measured on `web@3dba121` (staging, untouched main worktree) before either
ticket wrote a line. **A worker reporting "green" is only meaningful against
this.** Re-measure if the base branches move.

| Check | Result | Note |
|---|---|---|
| `pnpm lint` | **exit 0** — 0 errors, **265 warnings** | every warning is the design-token rule (`no-restricted-syntax`, hardcoded brand hex) |
| `pnpm typecheck` | **exit 0**, clean | — |
| `pnpm test` (vitest unit) | **exit 0** — 12 files, 217 tests, all pass | 12 test files against ~600 source files: the ~2% coverage the design keeps citing |
| `pnpm e2e` | **never run green** | that is `e2e_local_green`'s entire job |
| `./mvnw test` | not baselined | two lane builds were already holding `~/.m2`; a third would contend. Re-run per ticket at gate time in the worker's own worktree, which is the real gate anyway |

**Correction this baseline forces on the `diff_coverage_and_lint_gate` ticket
spec.** It says *"lint is blocking for web. Pre-existing lint errors fixed."*
There are **no pre-existing lint errors** — there are 265 warnings and zero
errors, so `pnpm lint` already exits 0 and is already "passing". Making lint
genuinely blocking therefore means `--max-warnings 0` (or promoting the rule to
`error`), which converts 265 warnings into 265 build failures across files that
ticket does not own. That is the real decision that ticket has to make, and it
is not the one its one-line spec implies. Flagged here so it is decided
deliberately rather than discovered at Gate 3.

---

## 4. Autonomy prerequisites

`scheduler_rules`: *"no phase > 0 ticket starts until all
`autonomy_prerequisites` are true."* Truth is tracked in
`agent-policy.yml → autonomy_prerequisites` and mirrored here with evidence.
**Flip a flag only when that ticket's gates have been re-run and seen green by
the orchestrator — a worker's claim is not evidence.** Record the commit sha
next to the flag when flipping it.

| Prerequisite | Ticket | Status | Evidence (sha) |
|---|---|---|---|
| Policy record | — | ✅ `agent-policy.yml` exists | — |
| Evidence bar | — | ✅ composable from the gates | — |
| Error tracking (regression signal) | `error_tracking` | ✅ **LANDED** | be `5e076b1` · web `50a2cc0` |
| e2e green locally (FE evidence) | `e2e_local_green` | ✅ **LANDED** | web `7c0a57b` (backend: no change) |
| Diff coverage + blocking lint | `diff_coverage_and_lint_gate` | ✅ **LANDED** (narrow reading — see §3) | be `ee56126` · web `34b47b7` |
| Scope manifest gate | `scope_manifest_gate` | ✅ **LANDED** | web `c815bc3` (backend: no change) |

> **FLAG AUTHORITY MOVED (operator decision, in-session 2026-07-26):** this
> table is now the LIVE TRUTH for `autonomy_prerequisites`. The flag block in
> `agent-policy.yml` is definitional only and frozen at its 2026-07-26 state
> (three flags true there, flipped before the scope gate existed; those edits
> stand as reviewable history). Agents never edit `agent-policy.yml` again —
> the scope gate hard-vetoes it, which is the correct posture the operator
> chose to keep. **All four autonomy prerequisites are now ✅ — Phase 0 is
> complete and Phase 1 is unblocked.**

**Phase 1 is blocked until all six are ✅.** No exceptions.

---

## 5. Next actions

### WAVE 5 — dispatched 2026-07-27 (Phase 3 close + recorded debts)

All fork the wave-4 tips `dbb1842`/`0d3ffb5`. Workers: no e2e, no servers.
No migration allocated (V152 stays unallocated; a ticket needing one asks).

| Ticket | Zone | Lane | Branch pair | Notes |
|---|---|---|---|---|
| `dashboard_recommendations` | webapp | 2 | `agent/dashboard-recs-lane2` | Phase 3 closer; carries the explains-why COPY CONSTRAINT (decision log, auto_enrolment ruling) |
| `auth_redirect_hardening` | auth | 3 | `agent/auth-redirect-lane3` | item (e): safeRedirect control-char divergence |
| `platform_debts` | platform | 4 | `agent/platform-debts-lane4` | stories-import eslint guard + error-tracking comment truth |

**Intake manifests:**

`dashboard_recommendations` (webapp, S, lane 2):
```
backend/src/main/java/com/bvisionry/pipeline/**       (founder-facing recommendations read: repository method + endpoint — three-layer)
web/src/app/(app)/app/_components/**                  (dashboard surface)
web/src/app/(app)/app/assessments/**                  (results surface)
web/src/lib/recommendations-types.ts  -api.ts  -keys.ts   (new)
web/src/lib/generated/api-schema.d.ts                 (integration-resolved)
web/src/lib/contract-check.ts                         (append)
web/e2e/recommendations.spec.ts                       (new)
```

`auth_redirect_hardening` (auth, S, lane 3):
```
web/src/app/(auth)/**
web/src/lib/auth.ts                                   (re-export only)
web/src/lib/sanitize-next.ts                          (ADDED declared-by-ask: the extracted guard)
```

`platform_debts` (platform, S, lane 4):
```
web/eslint.config.mjs                                 (no-restricted-imports: **/*.stories from non-test files)
backend/src/main/java/com/bvisionry/common/errortracking/ErrorEvent.java              (comment truth only)
backend/src/main/java/com/bvisionry/common/errortracking/ReportErrorEventRequest.java (comment truth only)
```

---

### WAVE 4 — dispatched 2026-07-26 (Phase 3 core + P0 sweep + platform debt)

All four fork the wave-3 tips `6f3a2bd`/`ad5079a`. Workers do NOT run e2e or
start servers; orchestrator runs Gate 4 serialized. **V151 conditionally
allocated to `auto_enrolment`** (idempotency/reason storage — nothing else may
take it). Serialising paths: none claimed (package.json/vitest.config are
`gate_platform_items`' alone this wave).

| Ticket | Zone | Lane | Branch pair | Notes |
|---|---|---|---|---|
| `auto_enrolment` | assessment | 1 | `agent/auto-enrolment-lane1` | L, SINGLE AGENT sequential (graph §8); V151 conditional |
| `ux_p0` | webapp | 2 | `agent/ux-p0-lane2` | sweep + the ui/select.tsx `items` platform fix (ratified) |
| `gate_platform_items` | platform | 3 | `agent/platform-items-lane3` | wave-3 items (a)(b)(d)(f) |
| `e2e_denial_anchors` | webtests | 4 | `agent/denial-anchors-lane4` | approved follow-on; e2e-only, orchestrator verifies |

Queued: item (e) safeRedirect (auth zone — after any lane frees),
`dashboard_recommendations` (webapp, after auto_enrolment), Phase 4.

**Wave-4 intake scope manifests (declared before any code):**

`auto_enrolment` (assessment, L, lane 1, V151 conditional):
```
backend/src/main/java/com/bvisionry/pipeline/**        (trigger: evaluation completion; mapping read)
backend/src/main/java/com/bvisionry/catalog/**         (enrolment write side — if enrolments live elsewhere, declare-by-ask)
backend/src/main/resources/db/migration/V151__*.sql    (conditional — expand-only)
web/src/lib/generated/api-schema.d.ts                  (integration-resolved)
web/src/lib/contract-check.ts                          (integration-resolved append)
web/e2e/auto-enrolment.spec.ts                         (new, if e2e-provable without UI)
```
Founder-facing surfacing belongs to `dashboard_recommendations` — this ticket
STORES the reason (triggering pillar + source evaluation), writes enrolments
idempotently, and REFUSES non-PUBLISHED courses (the pinned obligation).

`ux_p0` (webapp, S sweep, lane 2):
```
web/src/components/ui/select.tsx                       (ratified widening — the items wiring, platform item (c))
web/src/components/app/**  web/src/components/admin/** (Select call sites + empty-state sweep)
web/src/app/(app)/app/**                               (next-lesson CTA, breadcrumbs, dead-end empty states)
web/e2e/ux-p0.spec.ts                                  (new, optional)
```
Excludes: pricing/founder-content (never_touch), e2e/** others, lib/** except
colocated tests. P0 item 3 (role-aware home) is DONE (founder_dashboard).

`gate_platform_items` (platform, S, lane 3):
```
web/vitest.config.ts                                   (item a: exclude *.stories.* from coverage.include)
backend/src/main/java/com/bvisionry/config/SecurityConfig.java  (item d: pillar route-floor — AUDIT path shapes first)
web/src/instrumentation.ts  web/src/lib/error-report.ts (item f: digest on server-origin reports)
```
Item (b) DECIDED at intake: `pnpm test` does NOT run the storybook project —
stories are docs; their fixtures feed dom-project tests (the competency
pattern is the standard). The ticket documents this in the config comment.

`e2e_denial_anchors` (webtests, S, lane 4):
```
web/e2e/_helpers.ts                                    (assertIdentity)
web/e2e/console-surfaces.spec.ts  benchmarking.spec.ts  roi-report.spec.ts
web/e2e/competency-matrix.spec.ts  nudges.spec.ts      (the 6 enumerated vacuous blocks)
```
`release-flows.spec.ts` remains orchestrator-reserved.

---

### WAVE 3 — dispatched 2026-07-26 (Phase 2 remainder + Phase 3 head)

Phases 0–2 waves 1+2 are COMPLETE: **24 tickets integrated at backend `2ca01ae`
/ web `711b39f`** (see §2 LIVE TIP). Everything below §"Phase 1 — running
two-wide" in this section is historical record.

All four wave-3 branches fork the live tips. Workers do NOT run e2e or start
servers (host ceiling); the orchestrator runs Gate 4 serialized. `web/package.json`
(serialising path) is claimed by `sweep_fe_test_backfill` alone this wave.

| Ticket | Zone | Lane | Branch pair | State |
|---|---|---|---|---|
| `pillar_course_mapping` | assessment | 1 | `agent/pillar-course-mapping-lane1` | dispatched — V150 allocated |
| `competency_band_axis` (RULING 4 step 5) | webapp | 2 | `agent/competency-band-axis-lane2` | dispatched — no migration |
| `sweep_route_boundaries` | webroutes | 3 | `agent/route-boundaries-lane3` | dispatched — web-only |
| `sweep_fe_test_backfill` | webtests | 4 | `agent/fe-test-backfill-lane4` | dispatched — holds `web/package.json` |

Queued behind zone holders: `ux_p0` (webapp, after competency_band_axis);
`auto_enrolment` (assessment, after pillar_course_mapping — single agent,
sequential, per the execution graph §8); `dashboard_recommendations` (webapp,
after auto_enrolment). Phase 4 last.

**Orchestrator-owned in parallel:** the release-flows password-reset full-suite
investigation (§2's open Gate-4 exception) — run on a lane serving the
INTEGRATION tip, forensics captured at the moment of failure (tokens table,
Mailpit contents, backend log, trace). Not a worker ticket; it needs servers.

### Wave 3 — intake scope manifests (declared before any code)

**`pillar_course_mapping`** (assessment, M, lane 1, V150):
```
backend/src/main/java/com/bvisionry/pipeline/**       (pillar/band model + mapping admin)
backend/src/main/resources/db/migration/V150__*.sql   (its allocated number, only)
web/src/app/(app)/app/admin/pipelines/**              (admin mapping surface — declare-by-ask if the surface lives elsewhere)
web/src/lib/pillar-mapping-types.ts  web/src/lib/pillar-mapping-api.ts  web/src/lib/pillar-mapping-keys.ts   (new)
web/src/lib/generated/api-schema.d.ts                 (integration-resolved)
web/src/lib/contract-check.ts                         (integration-resolved append)
web/e2e/pillar-mapping.spec.ts                        (new)
```
Course side is read via raw SQL (NamedParameterJdbcTemplate precedent) — no
catalog imports, no frozen-store writes. Mapping keys on the pillar's own band
(ordinal POSITION, per RULING 4); the design must state what happens to
mappings when an admin later edits that pillar's band set.

**`competency_band_axis`** (webapp, S, lane 2 — RULING 4 step 5):
```
web/src/components/app/competency/**
web/src/lib/competency-types.ts  web/src/lib/competency-api.ts  web/src/lib/competency-keys.ts
web/src/app/(app)/app/_components/**                  (founder surface, if touched)
web/src/app/(app)/app/admin/insights/**               (cohort matrix panel)
web/src/lib/generated/api-schema.d.ts                 (integration-resolved)
web/src/lib/contract-check.ts                         (integration-resolved append)
web/e2e/competency-matrix.spec.ts
```
Backend zero-diff expected — `/api/pipelines/{id}/bands` already ships the
data. A backend change means asking the orchestrator first.

**`sweep_route_boundaries`** (webroutes, S, lane 3):
```
web/src/app/(app)/**/error.tsx                        (new files only)
web/src/app/(app)/**/loading.tsx                      (new files only)
web/e2e/route-boundaries.spec.ts                      (new, optional)
```
loading.tsx MUST use the shared `Skeleton` component — the a11y scan's settle
gate keys on it; hand-rolled `animate-pulse` divs were a scan-integrity veto
(gate4_determinism). Every error state names a next action.

**`gate4_env_hardening`** (orchestrator micro-ticket, no lane claim — declared
RETROACTIVELY at fix-cycle time; the validator correctly flagged that intake
should have preceded code. Recorded here so Gate 5 has its manifest):
```
backend/src/main/resources/application-sandbox.properties   (new profile-scoped file)
backend/src/test/java/com/bvisionry/aiconfig/service/RateLimitServiceTest.java
web/e2e/release-flows.spec.ts                               (the orchestrator-reserved file)
```
Plus HOST INFRA (unversioned, outside both repos, recorded here because
nothing else versions it): `docker/sandbox/sandbox.sh` env-heredoc and all
`agent-N.env` files now set `SPRING_PROFILES_ACTIVE=dev,mock,sandbox`. The
`sandbox` profile is LOAD-BEARING for lane e2e: without it the lanes revert to
production-shaped per-IP ceilings and the full suite re-acquires the 429
class. If a lane env file is ever regenerated by an older sandbox.sh, check
this line first.

**`sweep_fe_test_backfill`** (webtests, S, lane 4):
```
web/package.json  web/vitest.config.*                 (serialising — this ticket alone; jsdom coverage project)
web/src/**/*.test.ts  web/src/**/*.test.tsx           (new colocated tests)
web/src/test/**                                       (shared test setup, if needed)
```
First deliverable is the jsdom/browser coverage project (the recorded gap: the
diff-coverage gate excludes `.tsx` by construction). e2e positive-anchor
hardening for denial-only role specs: declare-by-ask with the enumerated spec
files; `web/e2e/release-flows.spec.ts` is RESERVED for the orchestrator's
investigation and may not be touched.

---

**PHASE 1 IS IN FLIGHT** (dispatched 2026-07-26). Phase 0 complete: all five
tickets landed, all four autonomy prerequisites ✅, integration green as a
combination.

### Phase 1 — running two-wide

| Ticket | Zone | Lane | Branch pair | State |
|---|---|---|---|---|
| `coach_console` | coaching | 1 | `agent/coach-console-lane1` (both repos, off `agent/integration`) | **✅ LANDED — be `b4b16ba` · web `90dfccf`** (3 cycles; RBAC veto cleared). See §3 entry. |
| `founder_dashboard` | webapp | 2 | `agent/founder-dashboard-lane2` (both repos, off `agent/integration`) | **✅ LANDED — web `fcae0f1`** (backend zero-diff, verified). See §3 entry. |
| `quantitative_benchmarking` | insights | 1 | `agent/quantitative-benchmarking-lane1` | **✅ LANDED — be `614064f` · web `08d0732`** (3 cycles; convergent double veto cleared). See §3 entry. |

Lanes 1 and 2 were reset to the seeded snapshot at dispatch. Both lane DBs
apply V145/V146 fresh on first backend boot (they fork integration).

### Phase 2 wave 1 — live state (updated 2026-07-26, mid fix-cycle 2)

All three lane branches REBASED onto the current integration tips (be `51788e0`
/ web `6721ac0`) by the orchestrator; all rebases clean. All three tickets are
in **fix cycle 2** (vetoes dispatched to their original workers; none parked).
Gate 4 queue after amends land, serialized one lane at a time:
roi (re-run — the amend invalidates its 118/118×2) → announcements →
courses_qa (flag env ON so the player suite executes).

| Ticket | Lane | Pre-amend shas (be/web) | Orchestrator-observed evidence | Panel verdicts | Fix cycle 2 scope |
|---|---|---|---|---|---|
| `roi_reporting` | 3 | `c4258d4` / `4de4141` | G4 **118/118 ×2** on seeded lane 3 (run 1 single release-flows fail = cold-`.next` first-run class; re-run needed post-amend) | tenant **VETO** · policy **VETO** · RBAC PASS · perf CLEAN | `founders()` users-join tenant predicate; `s.status='EVALUATED'` in BOTH twins (NEEDS_REVIEW quarantine leak); AUDIENCE hoist to common; javadoc/TZ folds |
| `announcements` | 4 | `4b4d40e` / `7448d27` | G1 exit 0 + cov 96.0% PASS · G2 re-run (regen = key-order churn only, reverted) · G3 lint 0/tc 0/**289 unit** + cov 83.3% PASS · G5 both repos PASS (6+9 ratified globs) | tenant PASS · policy PASS (sanitizer veto empirically closed) · RBAC **VETO** | server-side `flagged` suppression for coaches + pin; ACTIVE-status symmetry; `/**` route tails; filters-on MEMBER pin |
| `courses_qa_and_flag` | 1 | — / `60edb0d` (be zero-diff) | worker-claimed G1/G3/G5 green (web-only, 5 files); orchestrator re-gates at land | test-integrity **VETO** (convergent w/ UX lens) · UX advisory | bounded quiz `retry` + pure-function pin; zero-question Submit; mark-complete gate under empty states; copy/EmptyState/PageViewer folds |

Flag NOT flipped and premium-gate question → §7 items 0b/0c. Decision log
carries the full fix-cycle-2 entry set ("Fix-cycle 2 dispatched on all three
tickets").

**MANAGER-removal sequencing (decided at intake, expand-only):** V147 widens
the `users.role` CHECK to include `COACH` (keeping `'MANAGER'` in the allowed
set — pure expansion), migrates MANAGER holders to MEMBER as data, and code
drops the enum constant. The CHECK **contraction** (removing `'MANAGER'` from
the set) is deferred to a later operator-era migration — contraction migrations
are `never_auto_decide` and nothing needs it now: with the constant gone from
`UserRole`, nothing can assign the value.

### Standing procedure for every ticket from here (learned in Phase 0)

1. **Gate 4 needs a freshly-seeded lane** (`sandbox.sh reset <n>`), both
   servers on the lane's ports, `E2E_BASE_URL` never overridden. `rm -rf
   .next` first if the commit touched `globals.css` or design tokens.
   Budget: one suite run consumes 1 of the backend's 5/hour password-reset
   allowance — >5 runs/hour starves `release-flows`; the window is REDIS-backed
   (restart does not clear it; `FLUSHALL` on the lane's own Redis does).
   **HOST CEILING (learned the hard way, 2026-07-26): run ONE lane's app
   servers at a time.** Four concurrent lane stacks (4× spring-boot + 4× next
   dev + Playwright) exhausted the box — Turbopack died with native
   `0xE06D7363` crashes, bash could not fork (`MEM_COMMIT failed`), and the
   orchestrator process itself was killed mid-run, taking three in-flight
   workers with it. Workers are now told NOT to run e2e or start servers; the
   orchestrator runs Gate 4 serialized, one lane at a time. `rm -rf .next`
   cures a wedged dev server.
3b. **ALWAYS free the lane's ports BEFORE starting servers, and poll for a
   TRUE health signal — not merely "something answered".** Found 2026-07-26 on
   `competency_matrix`: stale lane-2 servers from an earlier phase were still
   listening, so BOTH new servers died with `EADDRINUSE` while the readiness
   poll (`curl -o /dev/null -w '%{http_code}'`, which accepts ANY response)
   reported `READY (poll 1)` — a cold backend needs ~40s, so an instant ready
   IS the tell. The suite then ran against the stale binary and failed at
   `auth.setup` (its sessions had been invalidated by the reseed). Same
   wrong-binary class as the doctrine below, reached from the opposite
   direction. **Procedure, binding:**
   ```bash
   # 1. kill anything on the lane's ports FIRST, verify empty
   netstat -ano | grep -E ":(<api>|<web>)" | grep LISTEN   # must print nothing
   # 2. start servers, then poll for a REAL signal
   curl -s http://localhost:<api>/actuator/health | grep '"status":"UP"'
   ```
   A poll that accepts any HTTP response cannot distinguish my server from a
   stale one. An instant READY on a cold start means the poll is lying.

4. **A web-only ticket's BACKEND worktree must be reset to the current
   integration tip before Gate 4.** Found 2026-07-26 on `gate4_determinism`:
   its backend worktree still sat at the fork point (`coach_console` tip), so
   the lane served a backend with no benchmark endpoints and the suite failed
   on "Failed to load benchmarks" — a green-looking stack testing the wrong
   binary, exactly the class Gate 4 exists to catch. Rule: if
   `git diff agent/integration..HEAD` is empty in a lane's backend, `git reset
   --hard agent/integration` there before starting servers; if it is NOT
   empty, rebase the ticket branch instead.
1b. **EVERY backend gate run must end with a frozen-store integrity check:**
   ```bash
   git diff <base> -- src/test/resources/architecture/    # MUST be empty
   ```
   Found empirically 2026-07-26 (nudges): **ArchUnit's `FreezingArchRule`
   auto-prunes violations it considers resolved, rewriting the `never_write`
   store WITHOUT the agent touching the path.** A transient edit to a
   `@RequiredArgsConstructor` signature on a class with frozen cross-feature
   deps was enough — the store lost 5 lines, the deletion survived reverting
   the source, and the reverted code then failed with 5 "new" violations that
   were actually pre-existing. `git checkout -- src/test/resources/architecture/`
   restores it. Related ruling (decision log → "the ratchet collision"): a store
   diff of ONLY REMOVALS is permitted when violations are genuinely resolved;
   **any addition = revert and stop.**

2. **Gate 5 is now mechanical**:
   `pnpm scope:check --repo <checkout> --base <stack point> --glob '<manifest glob>' ...`
   (exit 0 clean · 1 out-of-scope · 2 usage error incl. empty diff).
3. **Coverage claims need evidence**: quote the printed `threshold 70%` header
   and `TOTAL x/y` line; pass `--base <previous ticket sha>` or stacked
   commits dilute the measurement.
4. **Land → cherry-pick onto `agent/integration` → re-gate the combination.**
   Never trust a lane-only green (see the CRLF defect above).
5. **Validators**: 3 veto lenses (tenant-scoping, RBAC/3-layer,
   policy-compliance) + advisory as the diff warrants; each gets diff + spec +
   policy only, never the implementer's reasoning. Narrow the panel only when
   the diff cannot contain a lens's subject matter, and log that.
6. **Subagent model (operator instruction, 2026-07-26): every newly spawned
   subagent — workers and validators — runs on Opus 5** (`model: "opus"` on
   the Agent call). Agents resumed mid-cycle keep their existing session.

---

## 6. Standing rules — carried from the previous run's decision log

The previous run's branch (and its decision log entries) were deleted, but these
three decisions were sound and re-deriving them costs a park each. They bind
every ticket unless a human says otherwise:

- **`src/test/**` is in scope whenever the matching `src/main/**` is**, and
  `docs/agent-decisions.md` + `docs/agent-run-report.md` are in scope for every
  ticket. Without this no ticket can satisfy Gate 1 and Gate 5 at once. Encode it
  explicitly in the check that `scope_manifest_gate` builds.
- **One worktree pair per *agent*, not per ticket** — tickets stack as sequential
  commits, one per ticket, producing the reviewable stack the design asks for.
  `sweep_` fan-out still claims a pair per agent.
- **Error tracking is in-box** (Postgres table + SUPER_ADMIN endpoint), not a
  hosted tracker. `never_auto_decide` covers third-party dependencies that
  transmit user data off-box, and founder assessment data is exactly that.

---

## 7. Open gaps — known, not yet fixed

0. **OPERATOR RULING NEEDED — next-intl sits under `decisions:`, not `defaults:`.**
   The run's logged plain-English deviation formally covers
   `defaults.new_ui_copy`; but `decisions.i18n.new_surfaces_use_next_intl: true`
   is a CLOSED decision (`never_auto_decide` covers changing decisions), and
   next-intl is not installed anywhere in web — every UI ticket since Phase 0
   has been unable to satisfy it. The run continues under the logged deviation
   (reality: zero i18n infrastructure exists; installing it is a serialising
   platform ticket), but the closed-decision conflict needs a human ruling:
   either amend the decision or schedule the next-intl platform ticket.

**§7 items 0, 0b, 0c are RESOLVED** — the operator delegated ruling authority
on 2026-07-26 (each item advised by an independent fresh-context reviewer, then
implemented and documented). See `agent-decisions.md` → "OPERATOR-DELEGATED
RULINGS". Summary: **(1) i18n SUSPENDED** — English-only is now COMPLIANT, stop
logging per-ticket deviations; policy file amended with a reactivation trigger;
shipped tickets grandfathered. **(2) courses flag OFF** in staging/production
with a MECHANICAL flip condition (a SQL query that must return 0 — no judgment
required), ON ad-hoc in lanes/e2e only; `courses_qa_and_flag` is DONE and its
dependents are unblocked by the LANDING, not the flag. **(3) insights
entitlement: GATE BOTH** benchmarks + ROI before first ship — dispatched as
ticket `insights_entitlement_gate`. A fourth ruling (maturity bands) is in
flight. **The delegation is NOT standing: it covered the items parked as of
that date. Anything new still parks unless the operator delegates it too.**

0a. **AUTHZ AUDIT FINDINGS — scheduled, not yet fixed** (full entry: decision log
   → "sweep_preauthorize_audit"). 77 controllers / 397 handlers audited: no
   CRITICAL, no cross-tenant read, no reachable privilege escalation. Work that
   fell out, in priority order: **(1) the ArchUnit rule** requiring every
   `@*Mapping` to resolve to a `@PreAuthorize` — highest leverage, mirrors the
   existing data-layer rule, next platform ticket; **(2) H1** `showNames` is a
   client-only privacy control (incoherent, not a new data path — one guard in
   the display-name resolver fixes all 11 call sites); **(3) H3** download
   tokens carry full authority on any path for 60s; **(4) H2/M4** the route
   layer is effectively untested (20 of 21 authz tests run `addFilters=false`;
   51 controllers have zero coverage); **(5) M1** `LessonContentController` is
   the one bare handler set. Two deferred live verifications are named in the
   decision log.

0b. **OPERATOR RULING NEEDED — premium/tier gate on the insights surfaces.**
   `OrgInsightController` premium-gates every handler; the landed
   `BenchmarkController` and in-flight `RoiReportController` (roadmap sells ROI
   reporting in Founder Success, the top tier) call no entitlement guard — a
   FREE-tier org admin gets the report and both funder-facing exports. No
   ruling exists in the decision log despite a web comment claiming one.
   Gate/don't-gate both change what customers are charged or promised
   (`never_auto_decide`). Two-line expand-only fix whenever ruled. Full entry:
   decision log → "Premium/tier gating".

0c. **OPERATOR DEPLOYMENT DECISION — the courses flag.** `courses_qa_and_flag`
   landed QA + fixes but did NOT flip `NEXT_PUBLIC_COURSES_ENABLED` (it is a
   per-deployment env var, not code): the seed renders 46/70 lessons as empty
   states and the assigned-vs-self-selected distinction has no data model until
   `auto_enrolment`. Full entry: decision log → "Flag NOT flipped".

0d. **Backlog defects recorded by validators (pre-existing, not this wave's):**
   `OrgInsightController` accepts `showNames` with no server-side super-admin
   check (UI-only enforcement — any ORG_ADMIN unmasks founder names directly);
   `player-shell.tsx` lesson-content query masks errors behind a permanent
   spinner (same class courses_qa fixed one file down); `submissions
   (assignment_id)` unindexed (shared, documented deferral); announcements
   `FEED_CEILING=50` is also the only moderation surface (a reported post past
   50 becomes admin-invisible); `cohort_members` has no org-consistency
   constraint (application-layer defence pinned in two tickets now; DB fix is a
   `programflow` migration); insights routes have no dedicated route-layer
   matcher (goes to `sweep_preauthorize_audit`).

1. **Validator context purity is procedural, not enforced.** Validators must
   never see the implementer's reasoning; when an orchestrating agent relays
   between them it is itself the leak. Make it mechanical once
   `scope_manifest_gate` lands: drive validators from a script whose prompt is
   `git diff` + ticket spec + `agent-policy.yml` and nothing else.
2. ~~**Gate 4 has never passed.**~~ **RESOLVED at `e2e_local_green` (wave 1) and
   long since routine** — the combination suite now stands at **155 passed ×2
   consecutive** (§10). Struck rather than deleted because leaving it standing
   made this file assert something false about its own evidence, which is the
   exact defect class this run has spent six waves catching in code comments.
   The residual truth worth keeping: the frontend still lands largely on Gate 4
   rather than unit coverage — `.tsx` is ~84% of web churn and stays outside the
   diff-coverage gate until `sweep_fe_test_backfill` adds a component project.

---

## 8. THE STALE-CONSTITUTION TRAP (found 2026-07-27, orchestrator defect)

**Every lane worktree has been reading an OUT-OF-DATE `agent-policy.yml`.**

Mechanism: the governance docs (`agent-policy.yml`, `agent-decisions.md`,
`agent-run-report.md`) are committed on the BASE branch
(`claude/production-roadmap-requirements-xp8zsf`). `agent/integration` was cut
from an earlier point and has never received them. Verified:

```
$ git log --format=%H -3 -- docs/agent-policy.yml   # then, for each:
$ git merge-base --is-ancestor <sha> agent/integration
2a996cc ancestor-of-integration: NO   <- the operator-delegated rulings commit
a27528d ancestor-of-integration: NO
35b2ee0 ancestor-of-integration: NO
```

So a lane worktree shows `new_surfaces_use_next_intl: true` while the operator
SUSPENDED that clause on 2026-07-26. Any agent that reads the constitution from
its own worktree — which is exactly what every worker and validator is told to
do — sees a superseded document.

**How it surfaced:** the `calendar_integration` policy validator reported a
"discrepancy in my briefing" — the orchestrator's briefing said the clause was
suspended, the file in front of it said `true`. The briefing was RIGHT and the
artifact was STALE. A context-pure lens caught an orchestration defect that
three waves of agents had been silently absorbing.

**Blast radius, assessed honestly:** no landed artifact is wrong. The stale
deltas are (1) i18n suspension — its effect is "do nothing", which is what
happened; (2) the courses-flag mechanical trigger; (3) insights entitlement
gating — both already handled at orchestrator level. The damage was wasted
effort (per-ticket i18n deviation logging that was no longer required) and a
live trap: an agent could apply a superseded decision or re-litigate a closed
one and be *correct* according to the file in front of it.

**FIX — scheduled for wave close, deliberately not applied mid-wave:** sync the
three docs onto `agent/integration` in one commit while cherry-picking the wave.
Not done immediately because three tickets are being gated against integration
tip `c418963` right now and moving it mid-gate adds attribution noise for no
gain. **Standing rule from here: the docs must land on `agent/integration` in
the same pass as the code, or lanes branch from a lie.**

**Second-order lesson:** validators were given the policy's *content* in their
briefing as well as the path. That redundancy is what exposed the drift — a
validator told only "read the policy file" would have validated against the
stale one and reported PASS. Keep quoting the binding clauses INTO the briefing.

---

## 9. GATE 4 DOCTRINE — corrected 2026-07-27 (my previous rule caused failures)

**What went wrong.** After a clean run 3 (149 passed / 0 failed) I restarted the
web dev server "before the landing-decision run", per §standing procedure. Runs
4 and 5 then failed **9 specs each with 60 tests never reaching the runner**
(Playwright hit its failure cap). Code was byte-identical between run 3 and run
4 — the ONLY change was the restart.

**Two wrong hypotheses before the right one, recorded because the reasoning
matters more than the answer:**
1. *Cold compile.* Killed by run 5: a warm server reproduced the identical nine.
2. *Data/fixture drift.* Killed by direct inspection: the hardcoded seeded
   sub-org existed and was active, `orgadmin@bvisionry.com` was the ORG_ADMIN of
   its parent, and `GET /api/organizations/{subOrgId}` returned **200** both
   direct and through the BFF.

**Actual cause.** An abruptly killed Next dev server leaves a poisoned `.next`
cache that **404s dynamic nested routes**. Reproduced outside Playwright:
`/app/admin/sub-organizations/<id>/members` → 404 with a valid session while
`/app` → 200. `rm -rf .next` + restart → **200**, no code change.

**THE PROBE WAS INSUFFICIENT.** The recorded corruption signature is "anonymous
BFF call returns 500 where 401 belongs". It returned a clean **401** the whole
time while authenticated deep routes 404'd. That probe only exercises the
anonymous edge.

### Corrected standing procedure
1. **After any hard kill of the web dev server, `rm -rf .next` before restart.**
   A kill is not a clean shutdown.
2. **Probe an AUTHENTICATED DYNAMIC NESTED route**, not just the anonymous BFF —
   e.g. `curl -b "bv_access=$TOK" /app/admin/sub-organizations/<seeded-id>/members`
   must be 200. Keep the anonymous 401 probe; it catches a different corruption.
3. **Never make the first post-restart run the landing-decision run.** A restart
   is not free: the first run recompiles on demand and is the least reliable one.
   Run once to warm (expect a cold-compile casualty — run 6 lost one MARKETING
   route), then run for the decision. The old rule ("restart before any
   landing-decision run") was actively causing what it meant to prevent.
4. **Never capture a suite's exit code through a pipe.**
   `pnpm e2e 2>&1 | tail -25; echo $­{PIPESTATUS[0]}` printed `E2E_EXIT=0` for a
   run that had FAILED (`ELIFECYCLE ... exit code 1`) — `PIPESTATUS` was
   evaluated outside the subshell. Redirect to a log and `echo "REAL_EXIT=$?"`
   into it. A green exit code that is a lie is worse than a red one.
5. A failure spread across MANY unrelated specs, with tests "did not run", is an
   environment signature. A failure in ONE spec's own subject matter is a
   product signature. Attribute before re-running — and never re-run until
   green: that launders a real defect into a flake.

### Gate 4 harness rule (found on `calendar_integration`, applies to every spec)
Raw `page.request.*` NON-GET calls **403** from a context restored by `pageFor`.
`auth.setup.ts` saves only `bv_access`/`bv_refresh`; the BFF derives
`X-XSRF-TOKEN` from an `XSRF-TOKEN` cookie that a restored context has never been
issued. Proven against a live lane: bare → **403**, with the cookie (header
omitted, BFF derives it) → **200**. One GET through the BFF first mints it.
A browser never hits this — rendering any page mints the cookie — so only
raw-request tests are affected.

---

## 10. WAVE 6 = PHASE 4 — CLOSED (2026-07-27). [SUPERSEDED HEADING]

> This section once read "THE ROADMAP IS COMPLETE". That was true of the
> 24-ticket POLICY BACKLOG only. It was never true of roadmap.md §10 (UI/UX),
> nor of §11, and the operator has since redefined "done" to include §10 P0/P1/P2.
> Waves 7-10 followed this "completion". See the wave-9 close in
> agent-decisions.md for current state.

**LIVE TIP: backend `2afc53b` / web `06e6acc` — 39 tickets.**
(Backend code tip `6c1d37f`; `2afc53b` on top is the governance-doc sync, §8.)
Wave stack point was `c418963`/`f7b06ef`. V152 (sso) · V153 (coach_profiles) ·
V154 (org_branding) all present; lane 1 Flyway at **154** (V152 applied
out-of-order because calendar landed alone first — `out-of-order=true` is set,
a fresh DB applies them in sequence).

| ticket | lane commits | landed as |
|---|---|---|
| `calendar_integration` | be `1c17c9e` / web `90c3fc2` | be `4d82416` / web `7cda3fe` |
| `saml_oidc_sso` | be `d5775ab` / web `11b1c05` | be `d1e7a1a` / web `3d6d80f` |
| `white_label_theming` | be `ba03515` / web `3ccda56` | be `6c1d37f` / web `06e6acc` |

### Combination evidence — every gate orchestrator-run, none worker-claimed
backend **1158/0/0/0** · frozen ArchUnit store diff vs `c418963` **EMPTY** ·
web lint 0 / typecheck 0 / **772 tests, 59 files** · every per-ticket Gate
1/2/3/5 re-run and reproduced · **Gate 4: 155 passed / 1 skipped, TWICE
CONSECUTIVELY, exit 0 both** (§9 doctrine satisfied — and see the flake below
for why one green run would have been a lie).

### The white-label Gate 4 failure — root cause was Playwright, not the product
The open item at the last resume was `white-label.spec.ts` failing its
first-ever execution: *"the palette is in the SSR'd document"*, expected `> 0`,
received `0`. Reproduction first, as §9 requires: fetched `/app` server-side
with a live `bv_access` cookie and grepped the raw HTML. **The product was
correct** — the served document carried
`<style>[data-brand]{--primary:#b5179e;…}` plus the `.dark` and
`body:has([data-brand])` rules, and carried nothing at all once branding was
cleared. So the spec was wrong, and wrong for a reason worth recording:

```js
// playwright-core 1.60.0, verified verbatim in node_modules:
function shouldSkipForTextMatching(element) {
  return element.nodeName === "SCRIPT" || element.nodeName === "NOSCRIPT"
      || element.nodeName === "STYLE" || (document.head && document.head.contains(element));
}
```
`elementText` returns `""` for anything that function skips, so
`locator("style").filter({ hasText: "[data-brand]" })` matches **nothing, ever,
whatever the server sent**. It reads as an SSR assertion and is the constant 0.

**THE LESSON, and it is the wave's pattern again in a new costume.** There were
THREE call sites of that idiom. Two asserted `> 0` and failed honestly. One
asserted `.toBe(0)` — *"no branding means no injected style at all"* — and
would have been **green for ever while observing nothing**. This wave's named
pattern was "comments asserting a security property the code did not have";
this is its test-side twin: **an assertion whose matcher cannot see the thing it
names.** A green test is not evidence unless the assertion can fail.
Generalised rule now standing: *when an assertion's subject is invisible to the
tool doing the asserting, the test is decoration.* Prefer reading the artefact
(raw HTML, response body) over asking the DOM about things the DOM abstracts.

Fix: one `ssrBrandCss(page)` helper that fetches the served document and greps
the `<style>` blocks — which is also the *stronger* claim, since a DOM read at
`domcontentloaded` cannot distinguish server output from a client effect that
has already run. Falsified both directions live before the suite ran: branded →
style present; cleared → zero `data-brand` in the document.

### The second defect — found only because two runs were required
Run 1 of the pair: 155 green. Run 2: `"a portalled overlay is branded too"`
failed, `[data-slot="sheet-content"]` not found. Cause: the spec navigated with
`waitUntil: "domcontentloaded"` and clicked "Open menu" immediately — a click
on a painted-but-not-yet-hydrated trigger is a silent no-op. **The isolated run
passed and the first full run passed; only the second caught it.** This is the
whole justification for the two-consecutive-runs rule, now paid for twice.
Fixed by reusing the codebase's existing convergence idiom (`nav.spec.ts`'s
mega-menu, `robustFill`'s late-remount): poll, click only while still closed,
bounded by `toPass` so a genuinely broken menu still fails. Both selector
`[data-slot="sheet-content"]` and the 390px viewport — flagged unverified by
the worker — are now verified by three passing runs.

Both fixes were folded into the `white_label_theming` web commit (one commit per
ticket per repo holds; nothing has left this machine).

### §8 closed
The three governance docs are now on `agent/integration` (`2afc53b`), synced as
content rather than 80 doc-only cherry-picks (integration had never edited
`docs/` — verified empty diff). Lanes cut after this commit read the current
constitution: `new_surfaces_use_next_intl: false`, and the three
`autonomy_prerequisites` that had landed no longer read `false`.

### Roadmap status
All roadmap tickets are landed. **39 tickets on `agent/integration`, nothing
pushed** (LOCAL_COMMITS_ONLY — no remote has ever been touched). The wave-6
zones (auth, coaching, webapp/platform) are released.

### What is genuinely left — and it is all operator work
1. **Six decisions awaiting the operator** (listed below). None were
   auto-decided; the 2026-07-26 delegation covers only the four items parked as
   of that date, and every one of these is new.
2. **The integration branch has never been merged anywhere.** Landing it is an
   operator action by construction.
3. The follow-up backlog accumulated across waves 1–6 (§7), none of which is a
   roadmap commitment.

### Operator decisions still open (do NOT auto-decide)
- plaintext per-tenant `oidc_client_secret` at rest
- per-org SSO **enforcement** (today SSO is available, never mandatory)
- sub-org members are locked out of SSO (strict org equality, fail-closed) —
  correct-but-surprising; a customer with sub-orgs will hit it
- per-tier founder ceilings are sold in prose and enforced nowhere
- the Shibboleth repository trust surface (now allowlisted to `org.opensaml` +
  `net.shibboleth` via `.mvn/rrf/`, mutation-proven — but it is still a second
  binary source)
- `OrgAccessInterceptor:44`'s 36-char regex vs lenient `UUID.fromString`
  (audited: NO authenticated handler relies on the interceptor alone —
  defence-in-depth only, own ticket)
- **NEW (wave 7, 2026-07-27) — may an org admin ever see a founder's name?**
  `/dashboard/overview` already returns `memberName` AND `memberEmail`
  (`MemberScoreRow`) to every in-org admin. Wave 7's `showname_server_authority`
  enforces *"no unmasked EXPORTS"* and deliberately stops there: whether org
  admins may see founder identities AT ALL is a promise question
  (`never_auto_decide` → "anything that changes what a customer is promised"),
  and the 2026-07-26 delegation does not reach it. If the answer is "no", the
  export guard is a partial fix and the overview endpoint is the larger defect.

---

## 11. WAVE 7 — the security backlog (dispatched 2026-07-27, operator-chosen)

Roadmap-complete; this wave is recorded-backlog security work, not roadmap scope.
Both lanes cut from `agent/integration` AFTER the §8 doc sync — the first workers
this run to read a current constitution.

| Ticket | Zone | Lane | Branch pair | State |
|---|---|---|---|---|
| `showname_server_authority` | insights | 2 | `agent/showname-authority-lane2` | **LANDED** be `cb6e54c` / web `30c5e48` |
| `download_token_scope` | auth | 3 | `agent/download-token-scope-lane3` | **LANDED** be `14b368a` |

### THREE FINDINGS FROM WAVE 7 THAT OUTLIVE IT

**(a) THE FROZEN STORE IS NOT ACTUALLY IMMUTABLE — and now we know why.**
`archunit.properties` sets `freeze.store.default.allowStoreUpdate=true`. A
validator running mutations watched `ArchitectureRulesTest` **silently rewrite**
`src/test/resources/architecture/frozen-violations/7472acec-…` — pruned 20 lines,
added 8 — and its own mid-run "cross-feature violation" failures turned out to be
that self-contamination, not signal. That path is `never_write` by policy, so the
policy is currently enforced by MY DISCIPLINE ("check the store diff after every
backend run") rather than by the tool. The doctrine was right; the mechanism was
never understood until now. **Candidate fix: `allowStoreUpdate=false`, which makes
the ratchet a ratchet.** It is a config change with real consequences (no agent
could then add a violation even legitimately — which is arguably the point), so it
is FLAGGED, not made. Escalated.

**(b) MY OWN BRIEFING CONTRADICTED THE CONSTITUTION, all run.** I have told every
worker that `agent-policy.yml`, `agent-decisions.md` and `agent-run-report.md` are
orchestrator-exclusive. The constitution says otherwise: `scope.always_in_scope`
lists `backend/docs/agent-decisions.md` and `backend/docs/agent-run-report.md`
explicitly, with the comment *"Deliberately NOT here: agent-policy.yml and
agent-execution-graph.md"* — and `on_ambiguity` REQUIRES a worker to write the
decision log. My restriction was stricter than the rule and actively prevented
compliance with it: worker-level decisions reached that file only by passing
through my summarising, if at all. Found by a validator I had briefed with the
wrong claim, checking it against the source. **Corrected for any future ticket:
workers may and should write `agent-decisions.md`; only `agent-policy.yml` and
`agent-execution-graph.md` are off limits.**

**(c) A VALIDATOR DISAGREEMENT IS A SIGNAL TO RUN AN EXPERIMENT, not to pick a
side.** Two lenses contradicted each other on whether five URI shapes evade the
`/api/auth/**` matcher. One had probed `MockHttpServletRequest`; one had read
Spring's source. Against a real Tomcat, NEITHER was right: four shapes are
rejected **400 by the container before any filter runs**, and the case variant
**404s** (routes nowhere) while the canonical path returns 200. The truthful
conclusion — the exclusion holds as defence in depth, not because the matcher is
sufficient — was available to neither lens alone.

**Two backlog items were struck at intake, by verification rather than dispatch:**
- §7.0a item (1), the ArchUnit `@*Mapping` → `@PreAuthorize` rule recorded as
  "highest leverage, next platform ticket", **already exists and passes**
  (`ArchitectureRulesTest:199`).
- §7.0a item (5), `LessonContentController` recorded as "the one bare handler
  set", **is properly defended** — gated by ENROLLMENT at the data layer, with
  the reasoning already in the code, and deliberately not org-gated because the
  course catalog is cross-org by design.
A third, §7.0a item (2)'s prescription for `showNames`, was **wrong in a way that
would have shipped a regression** — see the decision-log ruling. Three of the
audit's five recorded items did not survive contact with the code they described.
The lesson is cheap and worth keeping: **a backlog entry is a claim about the
past, and this codebase moves. Re-verify before dispatching, never after.**

`download_token_scope` found MORE than was recorded: the filter misses the
`user.getStatus()` check that `JwtAuthenticationFilter` has (a SUSPENDED user
authenticates on an unexpired token), AND the token authenticates ANY method on
ANY path for its lifetime — one minted for a PDF will authorise a DELETE
elsewhere.

### New backlog, surfaced by the wave-7 validator panel (NOT fixed — out of manifest)

1. **A read-only credential still buys a permanent tenant account.** Found by the
   RBAC lens while attacking the "GET/HEAD only" claim.
   `GET /api/organizations/{orgId}/invitations` returns **raw invitation tokens**
   in `InvitationResponse`, and `POST /api/invitations/{token}/accept` is
   `@PreAuthorize("permitAll()")` **and** CSRF-exempt. So any leaked read
   credential — a download token in a proxy log, a `Referer`, browser history —
   converts into a permanent account inside the org, via a state change the
   leaked credential never made itself. **PRE-EXISTING and strictly harder after
   `download_token_scope`**, but not closed. The same shape applies to
   `GET /api/organizations/{orgId}/join-link`. Own ticket; the honest fix is
   probably that a list endpoint should never return the redeemable secret.
2. **`AuthService.login/refresh` and `SsoLoginService` never check
   organization-active** (they check `status`). A suspended ORGANISATION's active
   user can still MINT fresh tokens at login; they are then refused per-request
   by the filters. Inconsistent rather than exploitable — the filters hold the
   line — but the mint path and the accept path disagree, which is exactly the
   asymmetry `download_token_scope` just removed one instance of.
3. **`GET /api/gdpr/me/export`** remains reachable by a leaked download token
   (full personal-data export, and it breaks the `/pdf`|`/excel` convention that
   made a path allowlist untenable).
4. **H3 is PARTIALLY closed, and the code now says so.** The decision log's
   recorded remedies were "path-scope the filter, **or** mint with reduced
   authorities" — `download_token_scope` did NEITHER; it added an orthogonal
   method restriction. The token still carries its owner's full authorities on
   every non-`/api/auth` GET.
5. **IN-ORG FOUNDER ANONYMITY IS NOT DELIVERED, AND CANNOT BE BY AN EXPORT GUARD
   ALONE — OPERATOR DECISION (the eighth, and the biggest).** Wave 7 landed
   "no unmasked exports". It did NOT land "founders are anonymous to their org
   admin", and the gap is not narrow:
   - `/dashboard/overview` returns `memberName` **and** `memberEmail` as JSON to
     every in-org ORG_ADMIN;
   - workshop `/analytics`, `/live`, `/teams`, `/members/{userId}/answers` all
     return real names unguarded, on the same controllers as the guarded exports;
   - **the masked export is REVERSIBLE.** `OrgInsight{Excel,Pdf}Service` orders
     `Member 1..N` by `user.id` deliberately (to keep the mapping stable across
     the AI prompt and both exports). Sort `/dashboard/overview` by `userId` and
     the anonymised report re-identifies itself row for row.
   In-org anonymity appears in NO pricing copy, NO roadmap clause and NO policy
   decision — the only anonymity rule, `benchmark_anonymity: AGGREGATE_ONLY`, is
   about cross-org benchmarks. So either (a) it was never promised, and the guard
   is document hygiene — in which case the code must stop implying otherwise
   (done), or (b) it was, and this is one door in a room with six windows. That
   is `never_auto_decide` ("what a customer is promised") and was NOT guessed.
6. **Follow-ups, non-blocking:** workshop XLSX export has authority coverage but
   no content assertion (fixture seeds no exercises, so its Answers sheet is
   headers-only and a `doesNotContain` there would be VACUOUS — the ticket
   declared this rather than faking it; close by seeding one exercise + answer
   row). `verifySubmissionOwnership` returns **400** for an authorization refusal
   and 404 for absence, which is a mild existence oracle — pinned as-is by
   `anotherMemberCannotReadThisFoundersOwnReport` because changing a status the
   web app may branch on had no place in a comment-truth ticket.

### Servers/lane currently up (kill or reuse)
lane 1: backend :8181 (integration worktree), web :3011 (integration worktree).
Lane 1 DB seeded + migrated to 154. Branding restored to `null` after the
manual reproduction — the lane is where the suite expects it.
