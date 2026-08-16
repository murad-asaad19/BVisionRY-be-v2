# QA plan — everything this run built

For a QA agent running the product by hand. Written after 55 tickets across waves 9–11 plus the
earlier phases. **Ordered by risk, not by feature area** — if you run out of time, stop where you
are and the untested remainder is the least dangerous.

Backend `3c6cae3` · web `4e9132e`, both on `agent/integration`.

---

## 0. Before you start

### Environment
```bash
bash docker/sandbox/sandbox.sh up 1                     # lane 1 infra
set -a; source docker/sandbox/agent-1.env; set +a
cd backend && ./mvnw spring-boot:run                    # api :8181
cd web && rm -rf .next && pnpm dev                      # web :3011
```
**Never use port 5432 or lane 0** — that is dev data and is off limits. `sandbox.sh reset 1`
is the revert button if you corrupt fixtures.

### Accounts — all share the password `Password123!`
| Role | Email | Notes |
|---|---|---|
| SUPER_ADMIN | `admin@bvisionry.com` | Platform. **Has no `orgId`** — that matters, see §2.4 |
| ORG_ADMIN | `orgadmin@bvisionry.com` | Scoped to the seeded "General" sub-org |
| COACH | `coach.e2e@example.com` | Assignment-scoped |
| MEMBER | `exercise.tester@example.com` | Has exercise assignments |
| MEMBER (plain) | `member@bvisionry.com` | No fixture obligations |

Seeded sub-org id: `2a93054a-f352-4220-a321-a84063924096`.

### Warm every route before judging anything
The dev server compiles on first hit. A cold first request can return 500 or time out and it is
**not a bug** — it has already produced one false failure in this run. Hit each route once, then
test. If you see a single 500 that never reproduces, warm and retry before reporting.

### Do NOT report these — known, deliberate, or already routed
1. **`data-gr-ext-installed` hydration mismatch** in the dev overlay — that is Grammarly, not us.
2. **`/app/admin/exercises` has no nav link.** Deliberate; it is reached from a push notification.
3. **A root-org ORG_ADMIN sees no promoted console links.** Correct — `requireOrgConsoleAccess`
   admits an org admin only to a *sub*-org console, so promoting them would ship 404s.
4. **An ORG_ADMIN gets 404 at `/app/admin/organizations` and `/app/admin/workshops`.** Correct —
   those are platform-wide indexes; the org-scoped children are what they may reach.
5. **Pricing page says "encrypted and GDPR-compliant" and "24–48 hours".** Known-wrong copy in a
   policy-protected file, already escalated to the operator.
6. **Coach onboarding checklist CTAs link to the page they are on.** Known, logged, design call open.
7. **Breadcrumbs show a type noun ("Member", "Result") instead of a name for some ids.** Known
   ceiling — the backend has no by-id lookup for those. A raw UUID *is* a bug; a type noun is not.

---

## 1. Security and tenancy — run these first, they are the ones that matter

### 1.1 Cross-tenant isolation on the unified console
Wave 10 collapsed two admin route families that had *different* guards. This is the highest-risk
change in the run.
- As **ORG_ADMIN**, visit `/app/admin/organizations/<their own sub-org id>/members` → **200**.
- As **ORG_ADMIN**, visit `/app/admin/organizations/<any other org id>/members` → **404**.
- As **ORG_ADMIN**, visit `/app/admin/organizations/<a ROOT org id>/members` → **404**.
- As **ORG_ADMIN**, visit `.../settings` and `.../sub-organizations` on their own org → **404**
  (super-admin-only tabs).
- As **SUPER_ADMIN**, all of the above → **200**.
- **Old URLs must redirect, not 404**: `/app/admin/sub-organizations/<id>/members` → **307** to
  `/app/admin/organizations/<id>/members`. But `/app/admin/sub-organizations` with **no id** must
  still render its own page — it is a different resource.

**FAIL** = any 200 where the table says 404. That is a cross-tenant leak, stop and report immediately.

### 1.2 Founder name exposure on exports (`showNames`)
- As **ORG_ADMIN**, trigger any org export (insights PDF/XLSX, team dashboard, workshop answers)
  with `showNames=true` in the URL → **403**.
- As **SUPER_ADMIN**, same → **200**.
- Confirm an ORG_ADMIN's *masked* export really is masked in the file contents, not just the header.

### 1.3 Invitation tokens must not appear in a listing
- As ORG_ADMIN, `GET /api/organizations/{orgId}/invitations` (via the app or directly) → every row's
  `token` is **null**.
- The invitee's emailed link still works end to end.
**FAIL** = a token string in the listing. That is account takeover, not a leak.

### 1.4 Assessment answers vs everything else
The privacy policy now makes a precise promise. Verify it holds:
- ORG_ADMIN **cannot** read verbatim assessment answers (`/assignments/{id}/answers` → 403).
- ORG_ADMIN **can** read workshop answers and exercise sheets. (This is intended — the policy says so.)
- COACH can read exercise submission contents for **assigned** founders only; an unassigned founder's
  work must 404, not merely be hidden.

### 1.5 Enterprise SSO
- `/login` shows the work-email form ("Or sign in with your company account").
- An unknown domain does not error hard — it falls back to the login page with a readable message.
- In the SSO admin console (§2.3), confirm **no stored client secret is ever rendered**, in the
  form, the list, or the page source.

---

## 2. Wave 9 features

### 2.1 Subscription tiers and the cohort ceiling
- SUPER_ADMIN can set an org to **Starter / Growth / Founder Success** (four options incl. Free).
- **Every pre-existing PREMIUM org now reads GROWTH.** Nobody lost capacity.
- On **Starter**, create a second cohort in the same quarter → refused, with a message naming the
  tier and the ceiling. On **Growth**, allowed.
- **The meter spans the billing family**: creating a sub-org must NOT reset the ceiling. Try it —
  this is the defect the design was written to prevent.
- Existing cohorts over the ceiling are never disabled or hidden.

### 2.2 Enrolment override
- As ORG_ADMIN, open a member's Courses from the members roster; remove a course.
- The founder no longer sees it; their **progress is retained, not destroyed** (re-enrol and confirm
  completed lessons/quizzes survive).
- **Re-run the founder's assessment.** The removed course must NOT come back. This is the whole point
  of the ticket.
- Cross-tenant: passing your own orgId with another org's member id → 400, and that member's
  enrolment unchanged.

### 2.3 SSO admin console
- SUPER_ADMIN only; an ORG_ADMIN cannot reach it.
- Create an OIDC registration (secret required) and a SAML one (metadata required).
- **Edit the display name of each without re-supplying the secret / metadata.** This must succeed —
  it was impossible before, and it is the defect the ticket existed to fix.
- Changing `emailDomain` is visibly flagged as dangerous.
- Delete works; the audit trail records the change.

### 2.4 Super-admin no-org pages *(fix)*
As **SUPER_ADMIN**, visit `/app/admin/exercises`, `/app/admin/exercises/<id>`, `/app/admin/admins`,
`/app/admin/sub-organizations`. Each must **redirect to the organizations list**, not 404.
Previously all four dead-ended the platform's own administrator.

### 2.5 Legal surfaces
- `/privacy` and `/terms` render; both are linked from the footer, and "Terms of Service" points at
  `/terms` (it used to point at `/privacy`).
- **Consent copy appears before the first question** of an assessment, on both the signed-in flow and
  the anonymous `/a/<token>` flow, and names AI analysis + who sees the result.
- Read the policy against the product. Any sentence you can falsify is a P1 — the whole ticket was
  about a policy that contradicted the system.

---

## 3. Founder-facing links *(fix — verify in real email)*

Eleven backend call sites used to emit `/my/assessments/...`, a route that does not exist. Now
`/app/assessments/...`.

- Trigger **assessment assigned**, **reminder**, and **results ready**. For each: open the link in
  the in-app bell **and** the one in the email (Mailpit, lane 1 → `:8027`).
- Every link must land on a real page. **A 404 here is a P0** — it is the core founder loop and it
  was broken in production email for the entire life of the product until this fix.
- Also check the post-completion survey link, and the email-template **preview** in the admin console
  (its sample links were broken the same way).

---

## 4. Wave 11 — navigation and wayfinding

### 4.1 Breadcrumbs
- Visit a 6-level route: `/app/admin/organizations/<id>/members/<userId>/results/<submissionId>`.
- The trail shows **names, never raw UUIDs**. A type noun is acceptable (§0.7); a UUID is a bug.
- Every ancestor crumb is clickable **and lands somewhere real** — a crumb that 404s is worse than no
  crumb. As an ORG_ADMIN, platform-only ancestors must render as plain text, not links.
- Pages ≤2 levels deep show no trail.

### 4.2 Command palette
- `⌘K` / `Ctrl+K` opens it; there is also a visible rail entry and a mobile button.
- **As a MEMBER, it must list no admin destination at all.** Check the whole list.
- Every entry navigates to a page the current role can actually open.

### 4.3 Sidebar
- SUPER_ADMIN: five named groups, not ~25 flat items.
- **Sub-org-scoped ORG_ADMIN**: console destinations (Members, Coaches, Assessments, Exercises,
  Dashboard, Reports, Insights, Activity, Branding) are rail anchors, not buried a drill-in deep.
- Root-org ORG_ADMIN: unchanged (see §0.3).

### 4.4 Notifications page
- `/app/notifications` pages, filters unread, marks read, and deep-links exactly as the bell does.
- Older notifications the bell dropped are now reachable — that is the point of the ticket.

### 4.5 Post-login landing
Sign in as each role and confirm where you land: MEMBER `/app` · COACH `/app/coach` ·
ORG_ADMIN `/app/admin/sub-organizations` · SUPER_ADMIN `/app/admin/organizations`.
- **Open-redirect check**: `/login?next=//evil.test/steal` and `/login?next=/\evil.test` must both
  ignore the parameter and use the role default. Try CR/LF and tab characters too.

### 4.6 Empty states and onboarding
- With a fresh MEMBER, the onboarding checklist appears and names the next action.
- **Dismiss it with steps outstanding → it still appears (collapsed).** Complete the steps → it never
  returns. A checklist that lies about completion is the bug this was written against.
- Sweep for dead ends: every empty list, empty dashboard and zero-state should name a next action or
  who to contact. Report any that just says "None".

### 4.7 Mobile (375px)
- The org console tab bar is **one scrolling row**, not four stacked rows of sticky chrome.
- The mobile nav sheet reaches every destination the desktop rail does.

---

## 5. Earlier waves — never manually tested

Lower risk only because they have automated coverage; none has had human eyes.

- **Coach console** — a coach sees only assigned founders; roster, founder detail, submission review,
  comments. An unassigned founder must be invisible, not merely unlinked.
- **ROI report** — pillar movement per cohort, per-founder deltas, PDF and XLSX export.
- **Benchmarking** — cohort vs org vs platform distributions; cross-org data must be aggregate only.
- **Competency matrix** — 11 pillars per founder and per cohort, movement over time.
- **Auto-enrolment + recommendations** — completing an assessment enrols the founder in mapped
  courses and the dashboard says *why* ("recommended because of <pillar>"). Re-running must not
  duplicate.
- **Announcements** — org admin and coach broadcast to a cohort; opt-out respected; a coach can only
  address a cohort they hold.
- **White-label** — org admin sets logo + colour; the signed-in app renders in it, SSR'd (no flash of
  unbranded content).
- **GDPR** — profile page "Download my data" returns everything; "Delete my account" erases and
  anonymises. A super admin and the last org admin must be refused self-deletion.
- **Error tracking** — force a browser error (event handler / unhandled promise, not just a render
  crash) and confirm it reaches the in-box store. **The non-render path is new and has never been
  exercised by a human.**
- **Calendar** — founder books with their assigned coach.
- **Inactivity nudges** — a stalled founder is nudged on their preferred channel.

---

## 6. How to report

For each finding: the **URL**, the **role**, exact steps, what you expected, what happened, and
whether it reproduces after a warm-up. Attach a screenshot for anything visual.

Severity:
- **P0** — cross-tenant data access, a credential in a response, or a broken founder email link.
- **P1** — a policy/marketing statement the product contradicts; a dead end reached by a role that
  was admitted; lost founder work.
- **P2** — wayfinding, empty states, mobile layout, copy.

If a check in §1 fails, stop and report before continuing — everything downstream is suspect.
