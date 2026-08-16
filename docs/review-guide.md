# Review guide — `agent/integration`, 44 tickets

Written for a human opening this cold. Backend `d6304f1` · web `0a04602`.
Nothing has been pushed; no remote has been contacted; `staging` and `main` are untouched.

---

## 1. What you are looking at

An autonomous run that delivered the roadmap's 24-ticket policy backlog (phases 0–4), then a
security backlog you chose, then the eight rulings you made. 29 backend commits, 38 web commits,
one branch per repo. Every ticket was implemented by a fresh-context worker, gated by me
independently (a worker's green is a claim, not evidence), reviewed by fresh-context validators
given only the diff + spec + policy, fix-cycled, and cherry-picked onto integration — after which
the *combination* was re-gated.

**Evidence on the final tree:** backend 1216 tests 0F/0E · web 776 tests / 60 files · lint 0 ·
typecheck 0 · **Gate 4 (Playwright) 155 passed ×2 consecutive** · ArchUnit frozen store **0 added
/ 16 removed** across the whole run · 154 migrations, highest V155, only the intentional V84 gap.

**A completeness audit** (`agent-decisions.md` → the wave-8 section) checked all 38 lane branches
in both repos for stranded work and found none; verified no lane holds a different body for a
migration version integration also has; and confirmed all 206 web contract pins resolve.

---

## 2. What the gates do NOT prove — read this before trusting a green

- **No real identity provider.** SAML/OIDC is tested against stubs and a local `HttpServer`. That
  the decrypted client secret is *accepted by a real IdP token endpoint* is inferred, never
  observed. This is the single largest untested surface.
- **Most authz integration tests run `addFilters = false`.** They exercise method security, not
  the real filter chain. Where it mattered I probed a live server by hand (noted below).
- **The two new CI steps have never executed.** GitHub Actions cannot be run locally. Their shell
  logic was replayed by hand against real history; that is not the same as a CI run.
- **One export family has authority coverage only.** The workshop XLSX export asserts 403/200 but
  no *content*, because the fixture seeds a workshop with no exercises — so its Answers sheet is
  headers-only and a `doesNotContain` there would be vacuous. The ticket declared this rather than
  faking it. A masking regression in `WorkshopAnswersExportService.writeAnswers` would ship green.
- **Deeper-than-one-level org hierarchies are untested.** `OrgHierarchyPort` is one level by
  contract; a grandchild would be silently refused and no test says so.

---

## 3. Where the risk concentrates — read these first

In rough order of "most worth a human's eyes":

1. **`auth/sso/**` + `common/crypto/SecretEncryptionService`** (V152, V155). Per-tenant SAML/OIDC,
   client secrets encrypted at rest with a `v1:` key-version stamp. Note the standing hazard,
   documented in `application-prod.properties`: rotating `BVISIONRY_ENCRYPTION_KEY` makes stored
   secrets undecryptable. Before this run that degraded AI features; now it would break every
   enterprise SSO login. The version stamp exists so a future rotation is a backfill.
2. **`auth/jwt/DownloadTokenAuthenticationFilter`.** A URL credential in a query string. Now
   GET/HEAD only, never `/api/auth/**`, and refused for non-ACTIVE principals via a predicate
   shared with the cookie filter. **H3 is only PARTIALLY closed** — the token still carries its
   owner's full authorities on every other GET, and the code says so at the mint site.
3. **`common/security/ExportNameGuard`** + the 8 org-scoped export handlers. 403 unless
   SUPER_ADMIN. **This is document hygiene, not anonymity** — see §5.
4. **`organization/InvitationService` + `InvitationResponse`.** The admin listing no longer returns
   the redeemable token. Was a real privilege escalation, not just a leak.
5. **`config/SecurityConfig` + `SsoSecurityConfig`.** Two filter chains now. The second is scoped
   to `/api/auth/sso/handshake/**` with CSRF disabled *for that matcher only*.
6. **Migrations V147–V155.** Expand-only, append-only. V155 corrects V152's now-superseded header
   in prose rather than editing it (V152 is immutable).

---

## 4. Judgment calls where a worker corrected ME — re-examine these

These are the places my instruction was wrong and a worker pushed back. I ratified each; you may
disagree. They are the highest-value re-review targets because they are where the run's authority
structure was overridden.

| Where | I said | The worker showed | Outcome |
|---|---|---|---|
| `white_label_theming` | ground dark contrast on `#051647` | `#0b1840` is the lightest `.dark` surface and therefore the binding constraint | worker's value shipped |
| `showname_server_authority` | prop-drill `isSuperAdmin` into `analytics-panel.tsx` | its only route is `requireSuperAdmin`, so that drills a constant `true` through three components | shared default flipped closed instead — strictly more restrictive |
| `showname_server_authority` | make the guard an injected bean | `TeamDashboardController.<init>` is pinned *with its signature* in the frozen store; a tenth ctor param rewrites a `never_write` file | `static` guard |
| `download_token_scope` | consider a path allowlist | binaries come from 8 controllers across 6 packages with no shared prefix, and `/api/gdpr/me/export` breaks the convention — an allowlist would fail closed on a real surface | declined, method restriction only |
| `invitation_token_disclosure` | the contract pin will force `admin-types.ts` to change | `SameKeys` is key-based and blind to nullability — proven both ways | premise withdrawn; the security property is guarded by an integration test, not the pin |
| `platform_guards` | fail CI on `^+` added lines in the frozen store | red on 9 of 9 historical commits, because a stored violation embeds the full method descriptor | see §6 — I then got it wrong a second way |

---

## 5. Things that are TRUE but easy to misread

- **The export guard is not anonymity.** An in-org ORG_ADMIN still sees `memberName` and
  `memberEmail` via `/dashboard/overview`, and workshop `/analytics`, `/live`, `/teams`,
  `/members/{id}/answers` return real names unguarded. Worse, the masked export is **reversible**:
  `OrgInsight{Excel,Pdf}Service` orders `Member 1..N` by `user.id` deliberately, so sorting the
  overview by `userId` re-identifies every row. You ruled that org admins may see names, so this
  is consistent — but no comment in the tree should claim more, and three that did were corrected.
- **`OrgAccessInterceptor` still has an open under-match.** `0-0-0-0-1` parses as a UUID but fails
  the pattern, so the interceptor skips. **Deliberate.** It is defence-in-depth only (twice
  independently verified that no `/api/organizations/**` handler relies on it alone), and closing
  it would require rebuilding two branding falsification tests that use that exact path as their
  only way to falsify a `@PreAuthorize`. Pinned as a named residual.
- **A `permitAll()` + CSRF-exempt invitation-accept endpoint remains.** That is correct — a
  brand-new invitee has no session. The fix was to stop *listing* the token, not to gate redemption.

---

## 6. Where I was wrong, and what it cost

Recorded so you can calibrate how much to trust the rest.

1. **The stale-constitution trap, twice.** Governance docs live on a branch that is not an ancestor
   of `agent/integration`, so lanes read an out-of-date policy. I closed it, then reopened it the
   same day by committing new rulings to the roadmap branch only. Two workers caught it. The
   countermeasure that worked both times: quoting the binding clause *into* the worker's briefing.
2. **I shipped an unverified CI guard twice.** After two lanes independently reported that my `^+`
   spec was wrong, my "fix" — a raw sorted-set diff — was **strictly worse on the case my own
   comment claimed it handled**: 47 false "gained" on a commit whose message reads "baseline size
   unchanged (47/47)". A validator caught both the regression and the false comment. The shipped
   version normalises the descriptor and was verified against all nine historical store commits
   *before* landing.
3. **I extended a permission that should have stayed a practice.** I told workers they may write
   `agent-decisions.md` (the policy does allow it). One refused, arguing N parallel workers
   appending on N branches lands a conflicted stack on the operator. Two accepted — and landing
   them produced exactly that. The worker was right.
4. **Nine instances of one defect class** were caught across the run: *a comment asserting a
   security property the code does not have.* Five in one wave alone. One was mine. **Do not trust
   a comment in this tree because it is confident.** The ones that survived were each checked
   against the code by a validator, but treat that as a filter, not a proof.

---

## 7. Open, and yours

- **Blocked on you:** which existing PREMIUM orgs are Starter vs Growth. The tier-ceiling ticket
  cannot start without that backfill ruling — it is the one ruling of your eight not implemented.
  Note the domain has only `FREE|PREMIUM`; the three marketing tiers do not exist in code, and the
  pricing meter is a **cohort rate**, not a founder headcount.
- **The largest unbuilt risk is legal, not technical.** Founders are told nothing about what
  happens to their data: the shipped privacy policy covers only the contact form, there is no terms
  page, and no consent copy at assessment start — while the platform sends their verbatim answers
  to a third-party LLM, stores an AI-cheating verdict about them, retains superseded score and
  narrative snapshots, and shows their name, email and AI narrative to their org admin.
  `roadmap.md:352` already carries this, unticked.
- **`roadmap.md`'s checklist has 21 items and has never been ticked** — including items that
  demonstrably landed. My earlier claim that "the roadmap is complete" was true of the *policy
  backlog*, not of that checklist. It needs reconciling.
- **Sweeps recorded, none blocking:** AAD/domain separation on the cipher (a `v2:` stamp makes it a
  backfill); ~13 more frozen `audit` edges that are free ratchet wins; the workshop XLSX content
  assertion; the 24h access-token TTL; the CSP nonce pipeline.

---

## 8. Practical notes

- Both repos are checked out on `agent/integration`. Previous branches are intact:
  backend `claude/production-roadmap-requirements-xp8zsf`, web `staging` @ `3dba121`.
- **`origin/staging` has moved to `efddf13`** while web's integration forked at `3dba121`, so the
  merge will not fast-forward. Recommendation: review this branch *as gated*, then rebase/merge and
  **re-run every gate on the result** — conflict resolutions during a rebase are unreviewed changes,
  and all evidence above is against the current tree.
- The 38 lane branches per repo are kept as refs so each ticket's provenance stays inspectable.
  They are not pending work; the audit proved their content is on integration.
- Sandbox lane 1 (`:8181` / `:3011`) was stopped. `docker/sandbox/sandbox.sh up 1` brings it back;
  `reset 1` reseeds it.
- Per-ticket detail — what shipped, every validator finding, every mutation-red proof — is in
  `docs/agent-decisions.md` (~2450 lines, newest last). Live state and doctrine are in
  `docs/agent-run-report.md`. Closed decisions are in `docs/agent-policy.yml`.
