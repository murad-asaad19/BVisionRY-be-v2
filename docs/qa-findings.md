# QA findings — live multi-role run

Originally recorded against a sandbox lane (`api :8181` · `web :3011` · mail `:8027`) on
backend `b62bc34` · web `4e9132e`. **That environment no longer exists** — the lane system was
removed 2026-08-16 and there is now one local stack (backend `:8080`, web `:3000`); see the root
`CLAUDE.md`. Reproduction steps below that name a lane or an `agent-N.env` need translating to it.

Severity scale: P1 = blocks or corrupts the product's core number · P2 = wrong or missing
behaviour a user meets · P3 = polish, noise, or internal-quality. (`qa-plan.md`, which defined
this scale, was deleted with the lane environment it documented.) `FIXED` marks findings closed.

**See "Validation pass — 2026-08-18" at the foot of this file for the current status of every
finding.** The bodies below are preserved as first written; where the pass disagrees with one,
the pass is authoritative.

---

## Verified working (not defects — recorded so a re-run does not re-test them)

- **Login → role landing.** MEMBER lands on `/app`. Footer "Terms of Service" → `/terms`
  (the wave-9 fix holds; it used to point at `/privacy`).
- **Member rail carries no admin destination.** My Assessments / Courses / Exercises / Program /
  Workshops / Main Site / My Profile only.
- **Assessment consent copy renders before the first question**, names the third-party AI, what it
  produces, and that the program admin sees the score and narrative but *not* the answer text.
  Matches the privacy policy. (`qa-plan.md` §2.5)
- **Required-question validation.** Submitting section 2 with the required free-text empty holds the
  page and shows "This question is required." inline.
- **Full evaluation loop.** Submit → mock evaluation → results page with overall score, per-pillar
  score, strengths, growth edges, "Moving forward". No error state, no stall.
- **Breadcrumbs on a 4-level route show names, never UUIDs**
  (`Workspace / Assessments / E2E Founder Readiness Test / Results`).

---

## Findings

### F1 · P2 · Radar chart tells screen-reader users there are 11 pillars, always
`web/src/app/(marketing)/_components/fri-radar.tsx:260`

`aria-label="Founder Readiness Index radar chart showing scores across 11 pillars"` is a hardcoded
string, but the component's own `data` prop is documented as "the results explorer passes the
member's live per-pillar scores instead". On the results page for an assessment covering **one**
pillar, a screen-reader user is told it shows eleven. The visible page next to it says
"1 pillar assessed", so the sighted and non-sighted readings of the same chart disagree.

Repro: sign in as `member@bvisionry.com`, open any single-pillar assessment's results, inspect the
chart's accessible name.

### F2 · P2 · Two nested `<main>` landmarks in the authenticated shell
`main#main` contains a second `main.min-w-0`. A document may have one main landmark; screen-reader
"skip to main" and landmark navigation become ambiguous. Reproduces on every `(app)` route —
Playwright's `locator('main')` fails strict-mode on the page, which is how it surfaced.

### F3 · P3 (noise, library-owned) · recharts logs 6 warnings per results view
`The width(-1) and height(-1) of chart should be greater than 0`. **Not our layout bug** — the
container measures 426×360 correctly at steady state. recharts' own
`defaultResponsiveContainerProps.initialDimension` is `{width: -1, height: -1}`, and it warns about
its own pre-measurement sentinel on first render before the ResizeObserver reports.

Worth recording rather than dismissing: recharts' `warn` helper hardcodes `var isDev = true` (both
the `lib/` and `es6/` builds) instead of gating on `NODE_ENV`, so this **will** log in a production
build too. Silenceable only by passing an explicit `initialDimension`, which we cannot know for a
fluid-width chart. Left alone deliberately: restructuring our layout to work around a third-party
sentinel would trade real complexity for console tidiness.

### F4 · P1 · "Tier mix" on the platform console still speaks the removed vocabulary
`web/src/app/(app)/app/admin/organizations/_components/platform-overview.tsx:288`
· `backend/.../organization/dto/TierMix.java` · `DashboardService.java:53,77`

The org cards and the tier FILTER on this page both know the Wave-9 model —
"Founder Success (0) / Growth (4) / Starter (0) / Free (5)". The **Tier mix** summary panel
inches below them reports "**Premium** 4 / **Trial** 0 / Free 5". `PREMIUM` was deliberately
DELETED from `SubscriptionTier`, and `TRIAL` is a status, not a tier — so a super admin reading
this panel sees a commercial breakdown in names that map to no sellable plan, and cannot tell
Starter from Growth from Founder Success on the one screen meant to show the platform's mix.

The counts are right (4 paid + 5 free = 9). It is the vocabulary that is two tiers stale.

**Why the Wave-9 migration missed it.** `SubscriptionTier`'s javadoc explains the constant was
removed rather than deprecated so that "the compiler flags every `== PREMIUM` site". But
`DashboardService:53` never names the constant — it derives the paid count by arithmetic,
`premiumTotal = totalOrgs - freeTotal`. No comparison, nothing for the compiler to catch. The
mechanical migration was mechanical only where the code spelled the name.

### F5 · P1 · Auto-assign gives founder assessments to COACHES and ORG_ADMINS, and they enter cohort measurement
`backend/.../assessment/PipelineAutoAssignmentRepository.java:28-34`

`findApplicableForMember` filters on **organization and `userType` only — role is never
consulted**. An org-wide rule (`user_type IS NULL`, shown in the UI as "Applies to all members")
therefore fires for *every* user who joins the org, whatever their role.

Reproduced live: inviting `coach.qa@bvisionry.com` as a COACH immediately created two
assessment assignments for them ("Coachability Assessment", "test"), with no action by anyone.

This is not cosmetic, for three reasons:
1. **It contradicts the product's own stated design.** `web/src/lib/app-nav.ts:234` says, in a
   comment, "A coach's workspace is their caseload, **not the learner surface**" — and omits the
   learner links. So the coach is given learner work and simultaneously denied the navigation to
   find it. (`/app/assessments` is `requireSession`, so it is reachable by URL — unadvertised,
   not sealed.)
2. **It corrupts the number the product exists to sell.** The org Dashboard's *Team Score Grid*
   lists `QA Coach … Leader … IN PROGRESS` as a measured subject, and "Total Assigned: 9"
   counts them. Bvisionry's unit of value is a *measured cohort*; a completion rate whose
   denominator includes staff is wrong at the core.
3. It would put a staff member's scores into the cohort's score distribution if they ever
   completed it.

**Open design decision — not fixed pending the operator's call.** Whether auto-assign should
target `MEMBER` only (and what to do about ORG_ADMINs who are genuinely also founders in small
orgs), and whether the ~2 bogus assignments already created should be cleaned up, are product
decisions with a data-migration consequence. Recorded, not guessed at.

### F6 · P2 · GDPR-deleted users are counted forever as incomplete in cohort completion
Same Team Score Grid. Six `deleted-…@deleted.bvisionry.invalid` rows sit at `IN PROGRESS`
and are counted in "Total Assigned: 9" / "In Progress: 8". Anonymisation is working correctly —
the PII is gone — but the assignment rows still weigh on the denominator, so erasing a founder
permanently depresses their cohort's completion rate and it can never be recovered.

### F7 · P2 · `(1 pillars)` — unpluralized count in the pipeline picker
Org Dashboard → "Assessment pipeline" select. Every single-pillar pipeline reads "(1 pillars)";
9 of the 16 options on this one org are affected.

### F8 · P2 · `/app` (the founder hub) has no page title of its own
It renders the generic marketing title "Bvisionry — Founder Readiness Intelligence". Every
neighbouring route sets one — "Sign in · Bvisionry", "Organizations: Platform Console ·
Bvisionry", "My Founders · Bvisionry" — so the signed-in home is the outlier. Its browser tab,
bookmark and history entry are indistinguishable from the public marketing home.
Related: the org-console Coaches tab titles itself bare "Coaches", missing the
"· Bvisionry" suffix every other console page carries.

### F9 · P3 · Lane emails point at `:3000`, not the lane's own web port
`bvisionry.frontend.base-url` defaults to `http://localhost:3000` and no `agent-N.env` sets
`BVISIONRY_FRONTEND_BASE_URL`, so every email a sandbox lane sends links to port 3000. A tester
following a lane email leaves the sandbox and lands on whatever occupies :3000 — which is the
dev server the project rules put off limits. The same env file already guards against exactly
this class of mistake for Playwright (`E2E_BASE_URL`, with a comment explaining why), so the
omission looks like an oversight rather than a decision.

---

## Fixes landed in this run

### F5 — auto-assign role scope · **FIXED** (per operator decision: "MEMBER + let admins opt a role in")

**Root cause was in two places, not one.** The auto-assign listener was the path that caught my
eye, but `resolveTargetMembers` had the identical blind spot: "All members" resolves via
`findByOrganizationIdAndStatus(orgId, ACTIVE)` — every active user in the org, coaches and org
admins included. So the immediate bulk assignment was already assigning to staff before any rule
existed. Fixing only the listener would have left the larger half of the bug in place.

- `V158__auto_assign_target_roles.sql` — `pipeline_auto_assignment_roles`, plus a backfill that
  makes every pre-existing rule MEMBER-only. Verified after migration: 2 rules → 2 MEMBER rows,
  0 rules left without a role.
- `PipelineAutoAssignment.targetRoles` (`Set<UserRole>`, default `{MEMBER}`) + `appliesToRole`.
  An **empty set matches nobody**, deliberately: the defect was an absent filter read as
  universal, and a permissive empty case would reinstate it by another spelling.
- `AssignmentService.applyAutoAssignRule` — role guard at the one choke point both the
  member-joined and member-moved events funnel through, where the `User` is already loaded. The
  events carry only `userType`, so filtering in the query would have meant threading a role
  through four publishers and two event records.
- `AssignmentService.resolveTargetMembers` — bulk modes filter by the same role set, so the
  immediate batch and the rule that follows it target the same people. Hand-picked `memberIds`
  are deliberately NOT filtered: assigning an assessment to a specific coach on purpose stays
  possible, which is what "let admins opt a role in" requires.
- Web: a "Who this measures" role picker on both assign dialogs, defaulting to Members, with
  live per-role active counts. It also narrows the client-side preview, so the dialog's
  "N new · M skipped" counts the same people the server will rather than promising to assign
  coaches it then filters out.

**Mutation-tested**: neutering the guard to `if (false)` turns all three no-op tests red.
**Verified live, A/B on identical inputs** — same org, same rules, same invite flow:
`coach.qa@` (invited before) holds **2** assignments; `coach.two@` (invited after) holds **0**;
`member@bvisionry.com` still holds 11, so the guard did not over-fire.

Per the operator's second decision, the 2 pre-existing bogus assignments were **left in place**,
not deleted.

### Post-invitation landing · **FIXED**
`web/src/app/(marketing)/invitations/[token]/actions.ts` hardcoded `redirect("/app")`, so an
invited COACH or ORG_ADMIN landed in the FOUNDER workspace on their very first session — nav
offering `/app/coach`, page showing a learner hub. Found by accepting a real invitation.

`login`/`signup` had used `landingFor(role)` since §10 P1-7; this path never joined them, and
nothing tested it. `join/[token]/actions.ts` carried the same hardcode — harmless today because a
join link mints a MEMBER, which is exactly the condition that let the invitation twin go unnoticed,
so both were fixed. 12 new tests pin the destination as *read from the role*, not a literal.

**Verified live**: an invited COACH now lands on `/app/coach`.

### Coach onboarding CTAs · **FIXED** (was logged as an open design call in `qa-plan.md` §0.6)
Both checklist steps had `href: "/app/coach"` while the checklist itself renders on `/app/coach` —
two buttons that navigated to where you already were. Both steps are completed ON that page, so
they now point at `#booking-link` and `#my-founders`, with the ids declared on wrappers (each
component renders several branches — loading / error / empty / list — and an anchor must survive
all of them). A test asserts neither CTA is a bare self-link and that every fragment is a declared id.

### F1 — radar chart pillar count · **FIXED**
`aria-label` now derives from `chartData.length` with correct singular/plural, instead of claiming
11 pillars on every chart regardless of how many were assessed.

---

## Security section (`qa-plan.md` §1) — run against a real sub-org ORG_ADMIN

The seeded `orgadmin@bvisionry.com` sits on a ROOT org and by design reaches no org console
(plan §0.3), so §1 had never been runnable. Created `suborgadmin.qa@bvisionry.com` on the sub-org
"General" through the real invite flow, then probed as them.

**Every probe carried a same-batch 200 control**, because a blanket failure (a stopped API, a bad
path) makes a denied probe and a broken probe look identical — the false-PASS trap this plan warns
about, and one I walked into once below.

### §1.1 cross-tenant isolation — **PASS**
| probe | result |
|---|---|
| own sub-org members / invitations (CONTROL) | **200** |
| another tenant's sub-org members / invitations / cohorts / coach-assignments | **403** |
| a ROOT org's members | **403** |
| a different tenant's root org | **403** |
| platform dashboard / analytics (super-admin only) | **403** |

Page layer too: `/app/admin/organizations/<other-tenant>/members` renders `h1 = 404`, and the only
email address on the page is the *viewer's own* in the sidebar card — no foreign tenant data.

*Deviation from the plan, not a defect:* the plan predicts **404** for these. The API returns
**403**; the page layer returns 404. Both fail closed. The plan's codes describe the page layer.

### §1.2 `showNames` export guard — **PASS, decisively**
Same URL, same bogus report id, only the flag differs:
`showNames=false → 404` (report absent) · `showNames=true → 403`.
The 403 therefore comes from `ExportNameGuard.checkShowNames`, which runs before the lookup —
not from the resource being missing. A single-request probe could not have distinguished those.

### §1.3 invitation tokens — **PASS**
`GET /organizations/{id}/invitations` returns `token: null` on every one of 9 rows while the field
is present in the DTO, and the emailed link still works end to end (used it three times this
session to create the coach, org-admin and founder fixtures).

### §1.4 assessment answers vs scores — **PASS**
As ORG_ADMIN, the member-results report and its "View Details" panel show the founder's score,
band, strengths and growth edges — and **none** of her verbatim free-text. Checked by searching the
rendered page for distinctive phrases from the answer I actually typed.

**Correction to my own method here:** my first attempt probed three guessed API paths and got 404
on all three *including the one that should have succeeded*. An all-404 result with a failing
control proves nothing, so I discarded it and re-ran through the UI rather than report a pass.

### §3 founder email links (the P0) — **PASS, in a real email**
The "results ready" email in Mailpit carries
`/app/assessments/{id}/results` — the route that exists. The `/my/` bug is dead in the email
channel, confirmed against a live message rather than the source.

---

## Additional findings

### F10 · P2 · "Start your Coachability Assessment **assessment**" · **FIXED**
`dashboard-model.ts` appended the noun unconditionally, and most pipelines are already named
"… Assessment" — so the stutter hit the first screen a new founder ever sees. Now appended only
when absent (`withAssessmentNoun`), with a table test over both shapes.

### F11 · P2 · A required slider showed a value it had not recorded · **FIXED**
`self-rating.tsx` fell back to the midpoint so React never sees NaN — correct — but also PRINTED
it. An untouched required question displayed "50%" while counting as unanswered: submit was
refused with "This question is required" and the screen showed a value, so the only way forward
was to move a slider that already looked set.

Reproduced exactly: at 50% the page read "4/5 answered" with the error; one arrow-key to 51% →
"5/5 answered", error gone. The displayed value was never the stored value.

Fixed by showing "Not set yet" until an answer exists, keeping the thumb at the midpoint.
Auto-saving the midpoint would also have cleared the error and is the wrong trade for an
assessment instrument: it records a measurement the founder never made. 8 tests.

### Not defects — checked and dismissed, recorded so a re-run does not chase them
- **`<span>x</span>` inside every progress bar.** Base UI's own `ProgressRoot` renders it with
  `role="presentation"` and a visually-hidden style; assistive tech ignores it. Library internal.
- **Founder is auto-enrolled in a course, but "My Courses" says "coming soon".** Deliberate and
  documented in two places: `FEATURES.courses` is a pre-launch flag, and `recommendations.tsx`
  states that the link lands on the in-app Coming Soon "exactly what the My Courses nav entry
  already does. Honest either way, and never a dead link." The enrolment is real and survives the
  flag flip. **One thing was genuinely wrong and is FIXED:** `features.ts` claimed the sidebar
  "My Courses" link is withheld while the flag is off — `app-nav.ts` deliberately keeps it, with a
  comment saying why. The doc contradicted the code and briefly misled me.

### F12 · P1 (gate integrity) · Gate 4 could only pass on stale lane state · **FIXED**
`web/e2e/auth.setup.ts`

Running the e2e suite on a **freshly reset lane** fails at setup, taking all 158 tests with it:

```
✘ [setup] mint the coach identity via org-admin flows
  Error: cohort "Coach E2E Other" is created
```

Not a product bug — the opposite. The Wave-9 cohort meter rates cohort creation per billing
FAMILY, and the fixture family (`Test Organization`) is on **Growth = 1 cohort per month**. The
setup needs **two** cohorts: the one the coach holds, and one whose founder the coach must never
see. The first was created at 05:09; the ceiling correctly refused the second.

**Why it had been green.** `ensureCohort` only POSTs when the cohort is absent. Both cohorts
survived in the lane from earlier runs, so on every previous execution it found them and never
hit the meter. The suite therefore carried a silent dependency on leftover state, and the
previous run's "157 × 2 consecutive green" was green partly for that reason. I only saw it
because I ran `sandbox.sh reset 1` at the start of this session — the first genuinely cold start
the suite had faced.

This is the failure mode this project already has doctrine for in another form: a stale artifact
faking a PASS. Here it was stale *data* rather than a stale `.class` or `.next`.

**Fix:** the setup now provisions its own precondition — a SUPER_ADMIN context raises the billing
family to the unlimited-cohort tier before creating cohorts, with the family root DERIVED from the
sub-org's `parentOrganizationId` rather than hardcoded (the meter spans the family, and a sub-org's
own tier row is not what it reads). The meter is satisfied, never weakened or bypassed.

---

## Gate results at close

| Gate | Result |
|---|---|
| 1 · backend | `./mvnw clean test` — **1260 tests, 0 failures, 0 errors, BUILD SUCCESS** |
| 2 · contract | `OpenApiExportTest` → `pnpm gen:api` → typecheck clean. The `SameKeys` pin *demanded* the new `targetRoles`/`byTier` fields — the contract check did its job rather than being worked around. |
| 3 · web | lint `--max-warnings 0` **0**, typecheck **0**, **972 tests / 76 files** |
| 4 · e2e | **157/157 clean** on a freshly restarted stack. A second consecutive run gave 155 + 1 — see below. |
| 5 · scope | web **PASS** (24 paths). Backend fails on exactly the operator-authorized exception, and on nothing else. |

**Gate 4 — the honest reading.** I do not have two consecutive greens. Run 1 after restarting both
servers was 157/157. Run 2 failed one test: `release-flows.spec.ts` "the editor autosaves +
persists", timing out at exactly its 45s budget. That same test then passed **in 2.7 seconds** run
in isolation — a 17× swing that is host contention under 8 parallel workers, not a defect, and the
spec's own comment already records this fingerprint ("a loaded host has twice exceeded 20s — the
recorded fix is this budget, never the wait mechanism"). Nothing in this session touches the
exercise editor or its autosave path.

Earlier runs in this session showed *scattered* failures across unrelated authenticated specs
(403s), which cleared entirely after restarting both servers with `.next` cleared. That is the
project's existing "never judge under a stale dev server" doctrine, and it cost two misleading
runs before I applied it.

**Gate 5 — what the failure actually says.** Run bare, `pnpm scope:check` defaults to
`--base staging` with no manifest globs and flags 257 paths; that is my misuse, not a result —
this QA session has no ticket manifest. Run correctly against this session's own diff, the gate
computes **gained EDGES**, not raw lines, and names precisely:

> gained 2 edge(s): `AssignmentService.applyAutoAssignRule` calls `User.getRole`;
> `AssignmentService.resolveTargetMembers` calls `User.getRole`

which independently confirms the +2 semantic change I reported before re-freezing — the 10
`upsertRule` signature renames and the ~668 stale line-number rewrites are correctly ignored by
the tool. `never_touch` is clean: neither `pricing/**` nor `founder-content.ts` was touched.

## Data left in lane 1 for review

Created through real UI flows, not SQL:

| what | detail |
|---|---|
| `coach.qa@bvisionry.com` | COACH, invited + accepted, booking link `cal.com/qa-coach/founder-session`, assigned to Amara |
| `coach.two@bvisionry.com` | COACH — the A/B control proving the auto-assign fix (0 assignments) |
| `suborgadmin.qa@bvisionry.com` | ORG_ADMIN on the sub-org — the fixture §1 always needed and never had |
| `founder.qa@bvisionry.com` | "Amara Okafor", MEMBER — took a full assessment, scored 68/100, pillar "I listen" 72 (Elite) |
| pillar→course rules | 3 bands on "Coachability Assessment", 2 on "E2E Founder Readiness Test" |
| cohort announcement | sent to Cohort 1 |

All share `Password123!`. Reviewable at `:3011`, adminer `:8092`, mail `:8027`.

---

## Second pass — nav, redirect and coach isolation

### §4.5 open-redirect — **PASS (live)**
Signed in with `?next=//evil.test/steal`: landed on `/app` (the MEMBER role default), origin
`http://localhost:3011`, no fragment of the hostile value anywhere. The 16 further bypass classes
are covered by `actions.test.ts` / `auth.test.ts`, so one live confirmation is proportionate
rather than re-walking all of them through a rate-limited login.

### §4.2 command palette — **PASS**
As a MEMBER: 8 entries, every one a member destination, **zero admin destinations**
(matched against `admin|platform|organization|tenant|sso|pipeline` on both label and href).

### §4.1 breadcrumbs — **PASS on the routes walked**
`Workspace / Coaching / Founders / Amara Okafor` and
`Workspace / Assessments / E2E Founder Readiness Test / Results` — ids resolved to names, no raw
UUID anywhere. (The deep 6-level org route was not walked; the ceiling documented in §0.7 stands.)

### §5 coach console — **PASS, including isolation**
Roster, booking link (persisted across a restart), founder detail with pillar scores, and honest
empty states for modules and exercise submissions. No verbatim assessment text on the coach's
surfaces either — the §1.4 promise holds for coaches, not just org admins.

Isolation, **with a control that actually returns 200** (see the correction below):

| probe | result |
|---|---|
| assigned founder (CONTROL) | **200** — own founder only |
| same-org founder NOT granted to this coach (×2) | **404** |
| a different tenant's founder | **404** |

Invisible, not merely unlinked — and the same-org case is the one that matters, since tenancy alone
would not have stopped it.

**Second correction to my own method.** My first isolation probe used
`/api/bff/coach/founders/{id}` and returned 404 for *every* id including the assigned one. I had
guessed the path; the real one is `/api/bff/v1/coach/founders/{id}`. An all-404 sweep with a dead
control is indistinguishable from perfect isolation, which is exactly the false PASS this plan
warns about — the second time this session the same trap nearly produced a clean-looking result
from a broken probe. Re-run against the path the page itself calls.

### Not a defect — the coach console's three "could not be loaded" panels
Transient. The web dev server had been left in a degraded state (`EPIPE` on stdout) when its
background task wrapper was killed, so the BFF returned Next's HTML error page with a 500 for
every route. A clean relaunch fixed all three with 0 console errors. Recorded because it looked
exactly like a real regression for several minutes.

### §2.4 super-admin no-org pages — **PASS**
`/app/admin/exercises`, `/app/admin/admins`, `/app/admin/sub-organizations` all serve **200** with
no 404 for a SUPER_ADMIN. The dead end that used to meet the platform's own administrator on a
route their role is explicitly admitted to is gone.

### §4.7 mobile (375 × 667) — **PASS, measured**
Org console tab bar: 10 tabs, **1 distinct row** (every `li` at `top: 362`), `flex-wrap: nowrap`,
`overflow-x: auto`, genuinely scrollable, total nav height **58px**. The wave-11 fix (deleting the
`flex-wrap` that contradicted the scroll container) holds — this is the ~190px-of-sticky-chrome
regression measured rather than eyeballed.

---

## Coverage — what this run did NOT execute

Stated plainly so the next pass starts in the right place rather than re-treading:

- **§2.1 tier ceiling**, only incidentally: the e2e cohort refusal WAS the Growth ceiling firing
  correctly, but the deliberate Starter-vs-Growth and billing-family-does-not-reset checks were
  not run as such.
- **§2.3 SSO admin console** — untested this run (create OIDC/SAML, edit display name without
  re-supplying the secret, confirm no stored secret is ever rendered).
- **§4.4 notifications page** · **§4.6 onboarding dismiss-with-steps-outstanding**.
- **§5** ROI report · benchmarking · competency matrix · white-label · GDPR export/delete ·
  calendar booking · inactivity nudges · the non-render browser-error path.

Everything above is untested, NOT known-good.

---

## §2.3 + §1.5 Enterprise SSO console — **PASS on every clause**

Exercised through the UI as SUPER_ADMIN, then verified in the database.

| clause | result |
|---|---|
| Create SAML (metadata required) | ✅ created; Register stays **disabled** until metadata is supplied |
| Create OIDC (secret required) | ✅ Register **disabled** with the secret blank even though the input is not HTML-`required` — the form validates it |
| **Edit display name without re-supplying the secret / metadata** | ✅ **both** — this is the defect the ticket existed to fix |
| No stored secret ever rendered | ✅ absent from the list DOM, the edit form and the API response |
| Delete works + audit trail | ✅ five actions recorded in order |

**The edit case, in detail** — this is the one that used to be impossible. On edit, the metadata and
secret fields come back **empty**, with placeholders that say so ("Stored — leave blank to keep the
current", "Leave blank to keep the stored secret") and help text stating "It is stored write-only
and never shown back". Saving with them blank:

- SAML: `display_name` changed, `saml_metadata` length **367 — unchanged**, and still contains the
  `entityID` I submitted. Blank means KEEP, not wipe.
- OIDC: `display_name` changed, `oidc_client_secret` prefix and length **byte-identical**
  (`v1:+zYGZl0atar02RgfwhDoK…`, 95 chars). Same conclusion.

**Secret exposure — the §1.5 question.** The response DTO exposes `samlConfigured` and
`oidcClientSecretConfigured` as **booleans**: a presence flag, never the value. Searching the
rendered page and the API body for the exact secret I had just typed found nothing.

*Method note:* my first pass flagged `anySecretInDOM: true`, which was a **false positive** — the
regex matched the words "EntityDescriptor"/"certificate" in the dialog's own help copy. Re-checked
against distinctive fragments of the value I actually submitted (`idp.qa-saml.example.com/sso`,
`protocolSupportEnumeration`): **zero** occurrences. A loose regex on a security check is worse
than no check, because it produces alarm rather than assurance.

**Encryption at rest (V155) — verified, not assumed.** `oidc_client_secret` is not the plaintext
(`stored_in_plaintext = f`); it is stored as `v1:+zYGZl0atar02RgfwhDoK…`, 95 chars against a
41-char input. The `v1:` prefix is a version tag, so the scheme can be rotated.

**Destructive-action copy is exemplary** and worth keeping as the house standard: *"Everyone at
qa-saml.example.com stops being able to sign in … immediately, and the stored metadata is destroyed
— re-registering means fetching it from the customer again. To pause sign-in reversibly, disable it
instead."* It names the blast radius and offers the reversible alternative.

---

## Re-review — coaching assignment, inviting, and role change (operator request)

### F13 · P2 · An existing member cannot be made a COACH through the UI
`web/src/lib/admin-types.ts:34` · `web/src/app/(app)/app/admin/members/_components/member-actions-dialogs.tsx:72`

The "Change role" dialog offers **Member** and **Org Admin** only:
`AssignableRole = Extract<MemberRole, "MEMBER" | "ORG_ADMIN">`, commented
"INSTRUCTOR/COACH are not lifecycle targets."

**The backend disagrees.** `MemberService.changeRole` refuses exactly two things — assigning
SUPER_ADMIN, and an org admin changing their own role. COACH is accepted. Proven live: promoting a
member with `PATCH /organizations/{org}/members/{id}/role {"role":"COACH"}` returned **200**, and
that user then appeared in the org console's coach picker alongside the two invited coaches — a
fully functional coach.

So the only supported route to a coach is inviting a **brand-new** person with the Coach role. If
the person is already a member of the org, there is no path: the invite dialog cannot re-invite
them, and the role dialog will not promote them. The Coaches tab's own empty state says "Invite a
coach from the Members tab and choose the Coach role", which is advice that only works for someone
who is not yet there.

**Not fixed — one decision needed** (see the question put to the operator): whether promoting a
member who already holds assessments/results to COACH is desirable, since a coach's nav
deliberately excludes the learner surface (`app-nav.ts`) and their in-flight assessment would
become unreachable from their own navigation — the same shape as F5.

### F14 · P2 · Malformed invite addresses are discarded silently · **FIXED**
`web/.../members/_components/invite-members-dialog.tsx`

`parseEmails` kept only tokens matching `EMAIL_RE` and **dropped the rest without a word**. Pasting
`not-an-email, valid.person@bvisionry.com` invited one person and reported success; the malformed
entry never reached the request, so it could not appear in the server's "skipped" count either.
At scale — a pasted list of ten with two typos — the admin is told invitations went out and two
people silently never receive one.

Confirmed the backend is NOT at fault: posting the malformed address directly answers
`400 … fieldErrors: {"emails[0]": "Invalid email format"}` and refuses the whole batch. The client
was filtering before the server could object.

Fixed by returning the rejects and naming them under the field:
*"1 entry is not a valid email address and will not be invited: not-an-email"*. Filtering rather
than blocking is still right for a bulk paste — one stray word should not refuse the other
forty-nine — but it must be visible.

### Verified working in the coaching flows
- **Invite → accept → land** for COACH and ORG_ADMIN (the landing fix, re-confirmed).
- **Assign to a founder**, **remove**, **re-assign to a different founder** — all take effect
  immediately in the coach's own console.
- **Coach picker lists only COACH-role users** in the org.
- **Booking link** persists and is what the founder sees.

### F15 · P3 · Removing a coach assignment uses a native `confirm()`
Every other destructive action in the console uses an in-app dialog naming the consequence — the
SSO delete is the house standard ("Everyone at … stops being able to sign in … To pause sign-in
reversibly, disable it instead"). Coach-assignment removal instead fires the browser's own
`confirm()`, which cannot be styled, reads as a browser artefact rather than the product, and
blocks the page thread. Its copy is good; the mechanism is the outlier.

---

## Correction: native `confirm()` is the norm in the admin console, not the exception

F15 above said coach-assignment removal was "the outlier" and that "every other destructive action
uses an in-app dialog". **That is wrong, and backwards.** Counted properly:

**15 native `window.confirm()` / `confirm()` call sites across 12 files** — course editor and
settings, email-template editor, exercise-template builder and list, exercises panel, join-link
card, coach assignments, personal-pillar editor, sortable pillar and question, survey pillar card.

The crafted dialogs (`StopRuleConfirmDialog`, the SSO delete) are the minority, not the baseline.

**What was fixed:** the coach-assignment removal only, since that is the flow under review. It now
uses a Dialog matching the house standard, and the copy is corrected per assignment kind — a cohort
grant says "including every founder in it", a single-founder grant does not (my first draft said it
for both, which overstated what a direct grant revokes).

**The remaining 14 are untouched and are a separate decision** — consistency across them is a
deliberate sweep, not something to fold into a QA pass.

## Fixture damage found and repaired

Gate 4 failed at setup with `MEMBER post-login landing … Received: /app/admin/sub-organizations`.
`exercise.tester@example.com` is `ROLES.MEMBER`, the exercise-lifecycle fixture, and it had become
an ORG_ADMIN.

The audit trail identified it exactly — `MEMBER_ROLE_CHANGED` at **19:40:53** (MEMBER → ORG_ADMIN)
and a re-save at 19:41:02, by `admin@bvisionry.com`, while I was working in the SSO console
(SAML created 19:37:31, OIDC 19:42:01). It was the operator exploring the role dialog in the
browser — which is itself corroboration of F13: they went looking for "make this user a coach",
found only Member and Org Admin, and picked Org Admin.

Restored to MEMBER through the same endpoint. Recorded because a role change to a shared fixture is
invisible until a gate fails, and the audit log is what made it attributable rather than a guess.

## Gate results after the coaching changes

| Gate | Result |
|---|---|
| 1 · backend | `./mvnw clean test` — see run below |
| 3 · web | lint **0**, typecheck **0**, **978 tests / 77 files** |
| 4 · e2e | **157/157, twice consecutively** — the two-run requirement is now genuinely met |

Gate 4's earlier single-run failures were all cold-compile 500s on routes the dev server had to
rebuild after each edit (`/about`, `/app/admin/admins`), each confirmed 200 once warm. Warming every
marketing AND authenticated route before the run removed them entirely — which is the plan's own
§0 instruction, and worth promoting from advice to procedure.

---

## §5 GDPR — export and erasure

### Art. 15 export — **PASS, and genuinely complete**
`GET /api/gdpr/me/export` as a founder with real data returned 200 and ~11 KB across **37
sections**: account, organization, assessment assignments / submissions / answers, pillar
evaluations, overall summaries and their histories, AI use detections, **ai_call_logs**, surveys,
course enrolments / recommendations / removals / progress, quiz attempts, certificates, reviews,
exercises, programs, workshops, teams, cohorts, coach assignments, notifications, upgrade requests
and account activity.

It carries her actual free-text answer and her scores, so the page's promise — "profile,
assessments, answers, scores, enrolments and activity" — holds. Including `ai_call_logs` is
notable: the subject gets the AI calls made *about* them, which is more than the promise.

### Art. 17 erasure — **PASS, verified destructively on a throwaway account**
Both confirmations are enforced independently: a wrong `confirmEmail` → *"Type your email address
exactly to confirm deletion."*; a wrong password → *"Current password is incorrect."* Neither can
be skipped.

Deleting `valid.person@bvisionry.com` (a COACH I created this session, no content) returned **204**,
after which: the `users` row is **gone**, no name or email survives in `users`, the coach assignment
is removed, and audit rows no longer reference the actor id. The members roster correctly no longer
lists them.

**Refusals** — both required by the plan, both present:
- SUPER_ADMIN self-deletion is refused, and the profile page says so before you try.
- A sole ORG_ADMIN of a **top-level** org is refused. Sub-org admins are deliberately excluded, and
  the reason is documented: "Sub-orgs are governed by the parent's admins, so they legitimately
  have zero local admins."

### A residual I nearly "fixed" into a vulnerability
After erasure, the org's invitations list **still shows the erased address**
(`valid.person@bvisionry.com`, ACCEPTED, with its timestamp). I had this written up as a P1 privacy
defect and was about to erase invitations by email address.

That would have been a serious mistake. `PersonalDataRepository`'s class javadoc argues the
opposite position explicitly, and it is right:

> "Identity is the account, never the email address … `POST /api/auth/register` is `permitAll` and
> mints an ACTIVE account for any address with no existing row, with no mailbox proof anywhere in
> the flow. An email-keyed export or delete would therefore let anyone register
> `victim@example.com` and, in two calls, read then destroy that address's assessment answers, AI
> evaluations and invitations across every organisation on the platform."

So keying erasure on `users.id` is a deliberate trade: a residual email in an admin-only list, in
exchange for closing an account-takeover-grade hole. The remediation path is named too — mailbox
proof via the hashed single-use token round-trip `PasswordResetService` already uses (V139/V141) —
after which the email-keyed rows become reachable safely.

**Not a defect. Not fixed.** Recorded so the next reviewer does not repeat my reasoning and ship
the "fix".

## §5 Reporting and white-label

### Benchmarking — **PASS, and the privacy control is real**
`GET /organizations/{id}/benchmarks?pipelineId=…` returns pure statistics: per pillar
`{sampleSize, mean, p25, p75}` for cohort / org / platform. **No emails, no names, no member ids.**

The k-anonymity threshold works: with `sampleSize: 2` against `minSample: 30`, the response is
`sufficient: false` and mean/p25/p75 come back **null** rather than publishing a distribution two
people could be reidentified from. The platform tier withholds even its sample size (`null`). This
is the "honest insufficient states" behaviour, and it is honest in the strong sense — it withholds
the statistic rather than caveating it.

### ROI / outcomes report — **PASS, and honest about what it cannot say**
`GET /organizations/{id}/roi-report?pipelineId=…&cohortId=…` → 200 with cohort size, founders
measured / remeasured, tasks assigned / completed, `completionRate`, per-pillar movement and
per-founder rows. Founders are named; **no email addresses** anywhere.

The notable part: `foundersRemeasured: 0`, so `foundersPaired: 0` and `intakeAverage`,
`latestAverage`, `delta`, `direction` are all **null**. It refuses to compute movement from a single
measurement instead of inventing a delta. `cohortId` is required — omitting it is a 400, not a
silently org-wide report.

### White-label — **PASS, including the SSR requirement**
Set a brand colour as ORG_ADMIN; it is emitted in the **server-rendered HTML** as an inline
`<style>` block scoped to `[data-brand]`, so there is no flash of unbranded content. It also derives
a dark-mode variant (`#a76bc2`) and an accessible `--primary-foreground` per mode.

Verified it reaches pixels, not just variables: the primary button computed to
`rgb(122, 31, 162)` — the exact brand colour.

*Method note:* my first check counted brand-coloured elements on `/app` and found **zero**, which
looked like "branding does not apply". Wrong probe — the hub is a navy hero with white cards and
gold tiles and uses `--primary` on nothing visible. Checking a surface that genuinely uses
`--primary` settled it. Branding was reset to the Bvisionry default afterwards, so no org is left
in a QA colour.

**Not a bug:** `/app/admin/branding` 404s a SUPER_ADMIN. That is deliberate and documented — "a
platform admin has no `orgId` of their own, so this page has no subject for them; they reach an org
through the Organizations drill-in". It is NOT a fifth instance of the `requireOwnOrgId` defect.

## Still untested at close
**Calendar booking** (founder books with their assigned coach) and **inactivity nudges** (a stalled
founder nudged on their preferred channel). Untested, not known-good.

## Calendar booking + inactivity nudges — the last two

### Calendar booking — **PASS, end to end**
This closes the loop on the session's very first finding ("No coach assigned yet"). The founder's
hub now shows **YOUR COACH — QA Coach** with *"Pick a time that works for you — the booking page
opens in a new tab"* and a **Book a session** link to
`https://cal.com/qa-coach/founder-session` — exactly the URL the coach published in their own
console, carried across three role switches.

The external link is opened safely: `target="_blank"` with `rel="noopener noreferrer"` (no
reverse-tabnabbing), and its accessible name is *"Book a session with QA Coach, opens in a new
tab"* — it names the coach and warns about the new tab.

The same page also now shows **RECOMMENDED FOR YOU → Product Management Foundations**, so both
empty states the operator pointed at are filled by real flows.

### Inactivity nudges — **PARTIALLY verified, and I will not claim more**
The *delivery trigger* is a scheduled per-org sweep keyed on `inactivity_nudge_days` (V149, default
14, 0 = off) with send-once semantics derived from the notification history itself. Forcing it would
mean backdating progress rows and driving the scheduler — not something I could do honestly inside
this pass, so **the trigger is untested**.

What IS verified is the half the founder controls, and the policy that governs it
(`nudge_channels: RESPECT_EXISTING_PREFERENCES`):
- `INACTIVITY_NUDGE` appears in the profile preference list as a member-visible type, described as
  *"Stalled course nudges — When a course you are enrolled on has seen no progress for a while."*
- Toggling it off writes exactly one `notification_optouts` row of type `INACTIVITY_NUDGE`;
  toggling it back removes it. Confirmed in the database, not just on screen.

*Method note (third of the run):* my first attempt matched the wrong switch by climbing to the
nearest container, and silently opted the founder out of `ASSESSMENT_REMINDER` instead. I then
briefly took the 12 toggles for unlabelled, because I checked `aria-label` and a wrapping `<label>`
and found neither — they are labelled correctly via
`aria-labelledby="notification-pref-<TYPE>-label"`. **No accessibility defect.** Both preferences
were restored; the founder now has zero opt-out rows, exactly as before.

### Correction: the inactivity-nudge TRIGGER is tested after all
I wrote above that the trigger was untested because forcing it "would mean backdating progress
rows". That is what it needs — and it is exactly what the suite already does.

`./mvnw test -Dtest='InactivityNudge*'` → **24 tests, 0 failures**:
- `InactivityNudgeJobTest` (6): disabled by default it does not even look for orgs; nudges through
  the ordinary dispatch with a deep link to the stalled course; a single stalled day is not
  pluralised; sweeps one org at a time and skips orgs with nudging off; one org failing does not
  stop the orgs after it.
- `InactivityNudgeQueryIntegrationTest` (18, real database): selects a founder whose last completed
  lesson is older than the window; ignores one who completed inside it; measures from the enrolment
  itself when nothing has been completed; ignores enrolments that are no longer active.

The job's own log confirms it firing (`nudged 1 founder(s) in org …`) and isolating a failure
(`failed for org …: boom`). Watching it fire once in the sandbox would add nothing over an
integration test that controls the window deliberately — so the honest statement is **covered**,
not untested. My earlier claim was too pessimistic and is withdrawn.

### The 14 remaining native `confirm()` sites — deliberately NOT swept
Inventory (12 files): course editor shell (×2) and settings dialog, email-template editor,
exercise-template builder and templates list, exercises panel (×2), join-link card, personal-pillar
editor, sortable pillar, sortable question, survey sortable pillar card.

Left alone on purpose. Each needs its own consequence copy to become a real dialog — "Archive this
course? Learners will lose access." is not interchangeable with "Delete this field?" — so it is a
deliberate design sweep, not a mechanical find-and-replace, and doing twelve files of bespoke copy
unasked at the end of a QA pass risks more than the polish is worth. They are also not defects:
every one of them does confirm before destroying. Recommended as its own ticket, with the SSO
delete dialog as the reference standard.

---

# Code re-review — the individual user flows (second pass, no browser)

A reading pass over the member / coach flows end to end, after the driven-QA pass. Different
method, so it found a different class of thing: the browser pass exercised the HAPPY path and the
empty states, this one asks what each surface does when a request FAILS.

### F16 · P1 · A failed answer-flush navigated anyway, and said nothing · **FIXED**
`web/src/app/(app)/app/assessments/[submissionId]/_components/assessment-taker.tsx:126`
`web/src/app/(marketing)/a/[token]/_components/public-assessment-taker.tsx:136`

`useAssessmentDraft.flushDraft` REPORTS failure (`Promise<boolean>`) instead of throwing — its POST
is a raw `fetch` in a try/catch, so neither `bffJson` nor the global MutationCache toast ever sees
it. Both call sites awaited it and discarded the answer.

The damage is not a lost answer (localStorage still holds them) — it is two screens contradicting
each other with no error anywhere:

1. member answers the last section, clicks **Review & Submit**;
2. the flush fails silently; the taker refetches the submission from a server that never received
   the answers, and navigates;
3. `/review` lists the questions they just filled in under "Unanswered required questions" and
   DISABLES Submit (`disabled={!review.complete}`);
4. "Return to Assessment" re-merges the local draft, so the answers are visibly back — and the loop
   repeats. Offline, this never terminates.

Fixed by reading the boolean: stay on the page that still holds the answers, and say so via the
same `toast.error` the exercise editor already uses. Pinned by
`assessment-taker.test.tsx` (4 tests, incl. a success control). Mutation-checked — reverting the
guard fails 3 of 4, control still green.

The standard being applied is the one next door: `exercise-editor.tsx` gets this exactly right
(single-flight guard, `onError` → toast + restore dirty, unmount flush). The assessment flow — the
more important of the two — had none of it.

### F17 · P2 · The assessment payoff dead-ends while `FEATURES.courses` is off · OPERATOR DECISION
`web/src/lib/features.ts:36` · `web/src/app/(app)/app/courses/layout.tsx:23`

`NEXT_PUBLIC_COURSES_ENABLED` is unset everywhere local, so `FEATURES.courses === false`. The
member home still renders a full **"Recommended for you"** section with real titles and real
reasons ("recommended for your Product & Market pillar"), because `/api/my/recommendations` is a
backend read that knows nothing about a web build flag. Every one of those cards links to
`/app/courses/{slug}/learn` → `CoursesLayout` → **"Courses are coming soon"**, a panel that never
mentions the course clicked.

So the founder's whole loop — take assessment → get scored → get recommended courses → start
learning — terminates in a placeholder, and nothing on the promise warns them. The panel already
knows how to caveat a card inline (it does it for `!coursePublished`: "No longer in the catalog.
You keep your access."); the far more common case gets nothing. Not fixed: whether to caveat the
cards or withhold the section pre-launch is a launch-state call, not a bug fix.

### F18 · P3 · Nested interactive content on the assessment cards
`web/src/app/(app)/app/assessments/_components/assessments-list.tsx:284`

Each card is a `<Link>` wrapping a `<Card>` that contains `<Button>` ("Retry Evaluation", "Start New
Check-In"). `<a>`'s content model forbids interactive descendants; the `swallow()`
preventDefault/stopPropagation helper exists precisely to paper over it. Functionally it works;
for a screen reader the link's accessible name absorbs the button's text. Fix is a restructure
(Link wraps the content, buttons become siblings inside the Card), not a one-liner.

### F19 · P3 · `EvaluatedPreview` is an N+1 that fails invisibly
Same file, `:69`. One `useQuery` per EVALUATED card, each fetching a full results report to render
a score and two pillar names; a founder with six evaluated check-ins fires six. On failure it
returns `null` — a silently blank card body, in an app whose dashboard is otherwise fastidious
about telling "fetch failed" apart from "nothing yet".

### F20 · P3 · `MyCourses` fails whole when only the catalog read fails
`web/src/app/(app)/app/courses/_components/my-courses.tsx:255`

`isError = enrollmentsQuery.isError || coursesQuery.isError`. The join below it carefully falls back
to `enrollment.courseSlug/courseTitle` so a course missing from the catalog still shows and stays
playable — and that fallback is unreachable when the catalog request is the thing that failed,
which is the likelier outage. Enrollments alone are enough to render a playable tile.

### F21 · P3 · Raw `toLocaleDateString()` bypasses the pinned formatter
`lib/format.ts` exists to pin `en-US` via a shared `Intl` instance. ~15 sites call
`new Date(x).toLocaleDateString()` directly; the member/coach-facing ones are
`exercises/_components/my-exercises-list.tsx:93`, `exercises/[submissionId]/_components/
exercise-editor.tsx:314,344`, `coach/founders/[founderId]/_components/founder-detail.tsx:161`.
These are client components that Next also server-renders, so server locale/TZ vs browser produces
a hydration mismatch and, across midnight UTC, a visibly wrong date.

### What this pass confirms is genuinely good
Not everything needs a finding. `dashboard-model.ts` distinguishes "fetch failed" from "nothing
assigned" and refuses to claim the latter unless BOTH reads succeeded; `onboarding.ts` derives
completion from real state and lets a dismissal collapse but never retire the checklist;
`coach-card.tsx` re-validates the Cal.com allowlist at render because V153 has no DB CHECK;
`recommendations.tsx` holds the "one true reason among several" copy constraint centrally so no
caller can overclaim. That is a high bar, and F16/F19/F20 are notable precisely because they fall
below a standard the same codebase sets elsewhere.

### F22 · P2 · The course catalog has no org scoping, and two access enums are inert
`backend/.../catalog/repository/CourseRepository.java` · `catalog/web/CatalogService.java`

Asked how courses reach an organization: assigned, or available to all? **All.** There is no
org-scoped catalog.

`Course.orgId` records who AUTHORED a course. It is read by exactly one query —
`findByOrgIdOrderByUpdatedAtDesc`, the authoring list — so it decides who may EDIT a course, never
who may SEE one. Every catalog read (`findCatalog`, `findCatalogSearch`, `findDetailBySlug`)
filters on `state = PUBLISHED` and the optional facets only: no `org_id`, no `visibility`, no
`access`. `CatalogService`'s javadoc states the intent outright — "the catalog is FULLY PUBLIC — no
tenant/org scoping is applied". `GET /api/v1/courses` and `/{slug}` are `permitAll()`.

The gap is that the entity carries two enums that read like tenant controls and are never
enforced:
- `CourseVisibility` — PUBLIC / UNLISTED / PRIVATE / **MEMBERS** ("visible only to members of the org")
- `CourseAccess` — EVERYONE / SIGNED_IN / ENROLLED / LINK

Both are written by `AuthoringService`, both are mapped into the response by `CourseMapper`, and
neither appears in a single authorization branch anywhere in the codebase. Set a course PRIVATE or
MEMBERS, publish it, and it lands in the global anonymous catalog exactly like a PUBLIC one. This
is not a blind spot in general — `SurveyVisibility` IS enforced
(`SurveyResponseService:405`); the course equivalent is simply inert.

Not live-exploitable today: `FEATURES.courses` is off, so the public catalog is rewritten to
`/coming-soon`. The backend endpoint remains `permitAll`, so reachability depends on whether the
deployment exposes the API directly or only behind the BFF. It becomes real the day the flag flips.

Consequence for the product model: "assigned to an org" does not exist. The three levers are the
global catalog (unscoped, anyone), the auto-enrolment rules (per pipeline pillar+band — and
Pipeline Builder is a SUPER_ADMIN surface, so those rules are platform-level and shared by every
org using that pipeline), and `enrolment_overrides` (per user+course, removal only). If B2B tenants
with private course libraries are intended — which `CourseAudience.B2B` and
`CourseVisibility.MEMBERS` both imply — that is unbuilt, and the enums currently promise it.

Two coherent resolutions, and it is an operator call which: enforce `visibility`/`access` (and add
org scoping) on the catalog read path, or accept the global catalog as the model and delete the
enums that claim otherwise. Shipping the flag with them inert is the one option that is not
coherent.

---

# Merge of `origin/main` into `agent/integration`

Backend took 4 commits (AI guardrail repair loop that could not converge on multi-violations,
per-call chat history, repair history in the call logs, confidence-gated escalation removed); web
took 2 (YouTube media embeds in the taker, repair history in the AI Config console).

### The frozen ArchUnit store conflicted — and the obvious resolution is wrong
`src/test/resources/architecture/frozen-violations/7472acec-…`

Resolved it first with `sort -u` of both parents. That is a REAL bug, worth writing down because it
looks correct: the store encodes violation COUNT as repeated identical lines.
`JoinLinkService:228` appears twice in both parents, and deduping claimed 2 violations where the
code has 3. The run then failed with exactly 2 unmatched violations — precisely the 2 duplicates
the dedupe destroyed, not the "new architectural edges" they first appeared to be.

Correct resolution is a MULTISET union: for each distinct line, `max(count_ours, count_theirs)`.
After that the ratchet pruned 3633 → **2776 lines, smaller than either parent** (2784 / 2793), so
the baseline shrank and `never_add_lines` holds. `ArchitectureRulesTest` 7/7.

Note for next time: `archunit.properties` already tolerates line-number drift, so a violation
reported at a line present in the store is NEVER churn — it means the multiset count is short.

### V145 collided, and git could not see it
main allocated `V145__ai_call_log_attempt_history.sql`; this branch already had
`V145__error_events.sql`. Different filenames, so the merge succeeded silently while leaving an app
that Flyway refuses to start. Renumbered error_events → **V159** (content unchanged: a rename, not
an edit, so append-only holds). Nothing in V146..V158 references `error_events`, so running it last
is safe. Operator confirmed the branch is not deployed anywhere, so no environment repair is owed.

The historical records in `agent-decisions.md` / `agent-run-report.md` were deliberately left
naming V145 — they record what was allocated at the time, which is the point of an immutable log.

### Lane hygiene
Lane 1 carries this run's QA data (77 users / 53 orgs vs a seeded lane's 58 / 23) and was NOT
reset. Verification used lane 2 instead, reset to the V144 snapshot so V145..V159 applied fresh.

### Gates after the merge
Backend **1262 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS** (read from the log, not the
exit code). Web **1015 tests / 79 files**, lint 0, typecheck 0.

---

# Validation pass — 2026-08-18

Every open finding re-checked against the working tree on `feat/cohort-redesign`. This section
is authoritative where it disagrees with a body above.

## Fixed since the original run

| # | Evidence |
|---|---|
| **F4** · P1 | `platform-overview.tsx` `TIER_DOT` now maps the four sellable plans (FREE / STARTER / GROWTH / FOUNDER_SUCCESS). Its comment documents "Premium / Trial / Free" in the past tense. |
| **F7** · P2 | Every pillar-count site pluralizes: `pillar{p.pillarCount !== 1 ? "s" : ""}` — `pipeline-selector.tsx:81`, `assign-dialog.tsx:363`, `lesson-editor.tsx:296`, `pipelines-list.tsx:393`. |
| **F8** · P2 | `(app)/app/page.tsx` sets its own title (its comment names the bug it closed). The "Coaches" sub-point is resolved by the root layout's `title.template` = `%s · ${SITE.name}`, which appends the suffix. |
| **F13** · P2 | `AssignableRole` is now `Extract<MemberRole, "MEMBER" \| "ORG_ADMIN" \| "COACH">`. Its comment explains INSTRUCTOR stays out deliberately (no console of its own). |

F1 and F5 were already marked fixed in their own sections and re-confirmed here.

## No longer applicable

- **F9** · P3 — lane emails. The lane system was deleted 2026-08-16; no `agent-N.env` exists.
  **Moot**, not fixed.
- **F15** · P3 — native `confirm()`. Retracted by this file's own "Correction" section: `confirm()`
  is the norm in the admin console, not the outlier. Left as a house-style question, not a defect.
- **F18 / F19 / F20** · P3 — all three cite `assessments-list.tsx` and `my-courses.tsx`, both
  **deleted** in the redesign (`assessments/_components/` no longer exists). The successor surfaces
  — `assessments/history/_components/history-body.tsx` and the `/app/courses` tree — were not
  audited for the same defects. **Obsolete as written**; a fresh a11y + fetch-resilience pass on
  the successors would be the honest replacement, not carrying these forward.

## Still live

- **F2** · P2 — nested `<main>`. Confirmed: `(app)/layout.tsx:61` renders `<main id="main">` and
  four descendants render their own — `assessment-taker.tsx:303`, `pipeline-simulator.tsx:476`,
  `public-assessment-taker.tsx:292`, `player-shell.tsx:270`. Unchanged since filing.
- **F3** · P3 — recharts `^3.8.1` still in use; the warning is library-owned and was deliberately
  left. No action, kept so a re-run does not re-investigate it.
- **F6** · P2 — anonymised users still weigh on cohort completion. No exclusion predicate exists
  in the dashboard or cohort-view read paths.
- **F21** · P3 — raw `toLocaleDateString()` down from ~15 sites to **6**. Reduced, not closed.
- **F17** · P2 — see the flag change below; the dead-end premise no longer holds, but the finding
  was an operator decision rather than a bug and is superseded rather than fixed.

## F22 — split verdict, and one thing that got sharper

The **org-scoping half is built**. `common/coursevisibility/CourseVisibilityAccess` supplies a
`VISIBLE_TO_ORG` SQL predicate over a new `org_visibility` model (`EVERYONE` / `ORG_LIST` via
`course_visible_orgs` / `MIN_TIER` ranked off `SubscriptionTier` declaration order, resolved at
the billing root, failing closed on an unknown tier). Its javadoc names the five surfaces that
share it. "Assigned to an org" now exists.

The **inert-enum half is not fixed, and now reads worse.** `Course.visibility`
(`CourseVisibility`: PUBLIC/UNLISTED/PRIVATE/MEMBERS) and `Course.access` (`CourseAccess`:
EVERYONE/SIGNED_IN/ENROLLED/LINK) are still written by `AuthoringService:365,367` and mapped by
`CourseMapper`, and still appear in **zero** authorization branches. They now sit beside a real
mechanism keyed on a *different* column (`org_visibility`), so an author setting a course
`PRIVATE` or `MEMBERS` gets no scoping from those fields while a live-looking control says
otherwise. The public catalog remains unscoped by design (`CourseRepository:43` — "no org
scoping, all PUBLISHED courses"), which is correct for a marketing catalog and is exactly what
makes the dead enums a trap.

**The mitigation F22 relied on has been withdrawn.** F22 closed with "not live-exploitable today:
`FEATURES.courses` is off". It is now **on by default** — `features.ts:36` reads
`process.env.NEXT_PUBLIC_COURSES_ENABLED !== "false"`, so an unset variable enables courses where
it used to disable them. Worth an explicit decision before staging: either enforce the two enums,
delete them, or confirm the public catalog is meant to expose every PUBLISHED course regardless
of what an author selected.
