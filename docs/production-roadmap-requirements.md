# BVisionRY LMS — Production Roadmap Requirements

Assessment date: 2026-07-21
Scope: `BVisionRY-be-v2` (Spring Boot 4 / Java 21) + `BVisionRY-fe-v2` (Next.js 16 / React 19)
Roadmap source: product roadmap table (Core / Engagement / Reporting / Personalization / Access / Scale)

---

## 1. Executive Summary

The platform is **much further along than the roadmap implies**. Most "Core" and
"Engagement" items already exist in production-grade form — the LMS catalog,
player, quizzes, certificates, completion tracking, reminders, and the
assessment/pillar engine are built and hardened. The self-paced course
experience is **complete but feature-flagged off** (`NEXT_PUBLIC_COURSES_ENABLED`).

The real gaps cluster into four themes:

1. **The coach persona is unbuilt.** `INSTRUCTOR` and `MANAGER` roles exist in
   the enum but have no console, no nav, no coach↔founder relationship model,
   and (for `MANAGER`) zero wired authorities. "Coach view" and "Coaching
   calendar integration" both depend on fixing this first.
2. **Personalization is half-wired.** Auto-assignment exists for *assessments*
   (`PipelineAutoAssignment`), but there is no pillar→course mapping entity and
   no score-driven auto-enrollment into Develop modules. This is the critical
   path for the Automated Founder Success tier (deadline: end of Q2 2027).
3. **Scale features don't exist yet.** No i18n on either end, no per-tenant
   theming/branding (white-label is data-scoping only), no booking/calendar
   model.
4. **Operational hardening is close but not done.** Non-blocking lint, low test
   coverage floors, no e2e in CI, missing sitemap/robots/manifest, 24h
   non-revocable access tokens, sparse route error boundaries.

Recommended sequencing: **Phase 1 (launch core + coach view) → Phase 2
(personalization engine, hard deadline Q2 2027) → Phase 3 (scale: i18n,
white-label, booking)**. Detail in §7.

---

## 2. Roadmap Gap Analysis — item by item

Legend: ✅ built · 🟡 partial · ❌ missing · Effort: S (&lt;1 wk) / M (1–3 wk) / L (&gt;3 wk)

| # | Roadmap item | Status | Current state | What's needed | Effort |
|---|---|---|---|---|---|
| 1 | Content upload (video/PDF/slides) | ✅ | MinIO presigned upload (512 MB multipart), media dropzone, 12 lesson types incl. VIDEO/PDF/DOCUMENT | Verify the exotic lesson types (SCORM, WEBPAGE, ARTICLE) actually render in the player or remove them from authoring | S |
| 2 | Course structure (modules/lessons + mindset checkbox) | ✅ | `Course → Section → Content` with sequencing, publish states, per-content completion | "Mindset transformation tracking checkbox" = add a boolean reflection flag per lesson completion, or reuse the existing embedded-assessment mechanism | S |
| 3 | Founder dashboard | 🟡 | Member sees assessments, courses, program, workshops as separate hubs (`/app` NavCard grid) | A unified founder home: assigned modules, completion %, next-up item, pillar snapshot. All data exists — this is a composition/aggregation page + one BE aggregate endpoint | M |
| 4 | Coach view | ❌ | No coach surface at all; INSTRUCTOR limited to catalog authoring; MANAGER wired to nothing | Coach console (see §3–§4): coach↔cohort/founder assignment model, completion roster, reflection review queue | L |
| 5 | Reflection prompts | ✅ | Quizzes (pass %, attempts) + embedded pillar assessments per lesson (`Content.pipelineId`) | Optionally add a lightweight free-text "reflection" content type if quizzes are too heavy for some lessons | S |
| 6 | Completion certificates | ✅ | Certificate entity, PDF generation, public verification by number, FE certificate page | None | — |
| 7 | Reminders (untouched module in N days) | 🟡 | Email + Web Push infra, per-type preferences, ShedLock jobs (`ProgramNotificationJob`) | Add an inactivity rule: "no `ContentProgress` on an assigned course for N days → nudge". Infra exists; the specific trigger doesn't | S–M |
| 8 | Cohort completion analytics | 🟡 | Team dashboard, org insights, platform analytics exist for *assessments*; cohorts + enrollments exist as separate modules (soft-FK) | Join enrollment progress across cohort membership: completion rate per cohort per course, drill-down per founder. New reporting endpoint + admin chart | M |
| 9 | Pillar → module linking | 🟡 | Forward link exists (lesson embeds an assessment). Reverse link — pillar → recommended courses — does not | New mapping entity `PillarCourseMapping` (pillarId → courseIds, score-band condition) + admin UI to maintain it + "recommended for you" on founder dashboard | M |
| 10 | **Automated course selection (MVP)** — Q2 2027 hard deadline | 🟡 | `PipelineAutoAssignment` auto-assigns *assessments* on events; evaluation engine produces per-pillar scores + maturity thresholds | Score-driven auto-*enrollment*: on `EVALUATED`, read pillar scores, match against `PillarCourseMapping` rules, create enrollments, notify founder. Rules engine + idempotency + admin override UI | L |
| 11 | Self-paced library | ✅ | Fully built (catalog, enrollment, player, resume, progress) — **feature-flagged off** | QA pass, then flip `NEXT_PUBLIC_COURSES_ENABLED=true`. Add "optional/explore" labeling to distinguish assigned vs self-selected | S |
| 12 | Mobile friendly | ✅ | Tailwind responsive throughout, collapsing sidebar, sheet drawer | Add `manifest.json` (sw.js already exists → near-free PWA), device QA on player/video | S |
| 13 | Multi-language content | ❌ | Zero i18n on both ends; all copy hardcoded English | BE: locale on user/org, translation tables for course/lesson/pillar content. FE: next-intl (or similar), message extraction across ~560 files. Retrofit cost grows with every new page — decide early | L |
| 14 | Org-level custom content (white-label) | 🟡 | Every course/cohort is `orgId`-scoped; `parentOrganization` sub-org hierarchy exists; org admins can author | Missing the *look*: per-tenant branding entity (logo, palette, email sender), FE runtime theming (tokens exist in `globals.css` but are single-brand), de-hardcode `hello@bvisionry.com` etc. | M–L |
| 15 | Coaching calendar integration | ❌ | Nothing. "Coach" today = AI text assistant (SSE) | Decision needed: **integrate** (Cal.com/Calendly embed per coach — S–M) vs **build native** (availability, slots, booking, ics, reminders — L). Recommend integrate first, build later if needed | M or L |
| 16 | Cohort communications *(not on roadmap — identified gap)* | ❌ | No human-to-human messaging anywhere. Closest touchpoints: exercise comment review loop, workshop help requests, course reviews, one-way notifications, AI coach chat | See §5a — announcements, contextual discussion threads, coach↔founder messaging | M–L |

---

## 3. RBAC & Role Restructure

### Current state

- Enum: `SUPER_ADMIN, ORG_ADMIN, INSTRUCTOR, MANAGER, MEMBER` (mirrored in FE `src/lib/auth.ts`).
- Enforcement: `@PreAuthorize` on 65 controllers + `@orgAccess.isInOrg(#orgId)` tenant guard; FE has 3-layer defense (edge cookie check → server `requireRole()` returning 404 → backend).
- **Problems:**
  - `MANAGER` is dead — declared, assignable, grants nothing. A user given this role silently has member-level access.
  - `INSTRUCTOR` is authoring-only (catalog/quiz CRUD) — it is not a coach role.
  - No relationship model linking a coach to the founders/cohorts they coach — so even a wired role couldn't scope its data.
  - Persona vs role are conflated: "Founder" lives in `MemberType` (data tag), which is correct — keep it that way.

### Target role model

| Role | Persona | Scope | Key permissions |
|---|---|---|---|
| `SUPER_ADMIN` | Bvisionry platform team | Platform | Everything; org management, AI config, platform analytics |
| `ORG_ADMIN` | Program admin (accelerator/university) | Their org + sub-orgs | Members, cohorts, content authoring for their org, org analytics, reminders config, (later) branding |
| `COACH` | Coach / facilitator | Assigned cohorts/founders only | Read completion roster, review reflections/submissions, comment, run workshops, manage own availability. **Rename or replace `INSTRUCTOR`** — if content authoring must stay separate, keep `INSTRUCTOR` for authoring and add `COACH`; otherwise fold authoring into `COACH` |
| `MEMBER` | Founder (via `MemberType=FOUNDER`) | Self | Own assessments, assigned + self-paced courses, program, bookings |
| ~~`MANAGER`~~ | — | — | **Remove** (migration to set existing MANAGER users → MEMBER), or explicitly wire as team-lead (read-only team dashboard). Do not leave dead |

### Required work

**Backend**
1. Migration: rename/introduce `COACH`, remap or drop `MANAGER` (Flyway + enum change; audit `users.role` values first).
2. New entity `CoachAssignment` (coachUserId ↔ cohortId and/or founderUserId, orgId) — the scoping backbone for every coach endpoint.
3. New guard bean `@coachAccess.canViewFounder(#userId)` / `canViewCohort(#cohortId)` mirroring the `OrgAccessGuard` pattern.
4. Coach API surface: roster (founders + completion %), reflection/submission review queue, per-founder detail (reuse `MemberResultsController` data with coach-scoped authorization).
5. `@PreAuthorize` audit: every controller that says `ORG_ADMIN` should be reviewed for whether `COACH` gets read access.

**Frontend**
1. `src/lib/app-nav.ts`: add a coach nav section; `src/lib/roles.ts` labels.
2. New route area `(app)/app/coach/*`: dashboard (cohort roster + completion), founder detail, review queue.
3. `requireRole()` guards for the new area; keep the 404-not-403 convention.

---

## 4. User Flows (target)

### Founder
1. Receives invite / join link → signup (or Google SSO) → lands on **founder dashboard**.
2. Takes the FRI assessment (11 pillars) → AI evaluation → results (radar + pillar detail).
3. **Auto-assignment (Phase 2):** low-scoring pillars → system enrolls them in mapped Develop modules; dashboard shows "assigned to you because of Pillar X".
4. Learns: video/PDF lessons with resume, reflection quiz per module, mindset checkbox.
5. Inactivity ≥ N days on an assigned module → email/push nudge.
6. Completes a journey → certificate (PDF, publicly verifiable) → prompted to book a 1:1 coaching session (Phase 3).
7. Explores the self-paced library beyond assignments.

### Coach
1. Login → **coach dashboard**: assigned cohorts, per-founder completion roster, flagged/idle founders.
2. Opens a founder → pillar scores, module progress, reflection answers → leaves feedback (exercise comment loop already exists).
3. Runs live workshops (already built).
4. (Phase 3) Sets availability; sees upcoming booked sessions.

### Org admin (accelerator / university)
1. Manages members, cohorts, join links, sub-orgs (built).
2. Uploads org-scoped content; assigns coaches to cohorts (new).
3. Cohort completion analytics: completion rate per cohort, per module, exportable (new reporting endpoint; export infra exists).
4. Configures reminder cadence + email templates (built).
5. (Phase 3) Brands the tenant: logo, colors, sender identity.

### Super admin
Platform orgs, AI config, prompt templates, pipelines, analytics — all built. Add: pillar→course mapping admin, feature flags per org.

---

## 5. Design / Architecture Restructure

Major changes are on the table; these are the ones actually worth making:

### Frontend
1. **Split `src/components/site/`** (43 files) — separate marketing chrome from app-shell components; it's the one folder that fights the otherwise clean feature-folder layout.
2. **Founder dashboard as the `/app` home** — replace the NavCard grid with a real aggregate dashboard for members (keep the grid for admins).
3. **Coach console area** — new route group section as per §3.
4. **Finish or remove `next-themes`** — dark tokens exist but no toggle; half-wired deps rot. If white-label (Phase 3) is coming, keep it and make token theming runtime-injectable per tenant.
5. **Route-level `error.tsx` / `loading.tsx`** across `/app` — today most errors fall through to `global-error.tsx`.
6. **i18n decision now, even if execution is Phase 3** — every new hardcoded string increases the retrofit bill. Minimum: adopt next-intl for *new* surfaces (coach console, dashboard) immediately.

### Backend
1. **Resolve `MANAGER`** (§3) — dead roles are a security smell.
2. **New `coaching` module** (vertical slice, matching ArchUnit conventions): `CoachAssignment`, coach endpoints, later availability/booking.
3. **New `personalization` concern** in the assessment module: `PillarCourseMapping` + auto-enrollment handler listening on evaluation-complete events (pattern already exists in `AutoAssignmentEventHandler`).
4. **Soft-FK integrity job** — catalog/programflow deliberately use UUID soft-FKs; add a scheduled orphan-detection job (enrollments→deleted courses, `Content.pipelineId`→deleted pipelines) so integrity drift is visible.
5. **i18n data model (Phase 3)**: `locale` on user + org default; translation side-tables for course/section/content/pillar/question text rather than column explosion.
6. **Branding entity (Phase 3)**: per-org logo, palette tokens, email sender/reply-to, custom domain slug; FE serves theme by tenant.

---

## 5a. Cohort Communications (identified gap, not on the roadmap)

### Current state

There is **no human-to-human communication channel** in the product. What
exists today only brushes against it:

- **Exercise comment loop** (`ExerciseComment`) — reviewer feedback on
  submissions, scoped to one exercise. Closest thing to threaded discussion.
- **Workshop help requests** (`WorkshopTeam.helpRequestedAt`) — a raised hand,
  not a conversation.
- **Course reviews** — public ratings, not discussion.
- **Notifications** (email + web push) — strictly one-way, system-generated.
- **AI coach chat** (SSE streaming) — AI only, no humans involved.
- The marketing site has a WhatsApp floating button — implying support
  conversations currently happen *off-platform*.

So a founder cannot ask their coach a question, a coach cannot address their
cohort, founders cannot discuss a module with peers, and an org admin cannot
broadcast to a cohort — all core expectations of a cohort-based program.

### Options

| Option | Pros | Cons |
|---|---|---|
| **A. External community** (Slack/Discord/WhatsApp groups, Circle) | Live in days; zero build | Fragments UX, no completion-context, breaks white-label, no data ownership, per-cohort setup toil |
| **B. Native, lightweight (recommended)** | In-product, context-aware ("discuss this module"), white-label-safe, reuses existing notification + sanitizer + SSE infra | Real build effort; moderation responsibility |
| **C. Full real-time chat platform** | Everything | Heavy (WebSocket infra, presence, moderation tooling); overkill for cohort learning |

### Recommended scope (Option B, three increments)

1. **Cohort announcements** (S–M): coach/org-admin → cohort broadcast.
   `Announcement` entity (cohortId, authorId, body, pinned), fan-out through the
   existing `UserNotification` + email/push preference system. One-way; cheapest
   high-value win.
2. **Contextual discussion threads** (M): threads attached to a context
   (`contextType`: MODULE / TASK / LESSON / COHORT, contextId) rather than a
   free-floating forum. Founders ask questions where the confusion happens;
   coaches answer once for the whole cohort. Generalizes the proven
   `ExerciseComment` pattern.
3. **Coach ↔ founder direct messages** (M): `Conversation` + `Message`,
   participants constrained by `CoachAssignment` (§3) — a founder can only DM
   their assigned coach, keeping the surface safe and support load bounded.
   Founder↔founder DMs deliberately excluded initially.

Delivery: polling/refetch first (TanStack Query intervals), SSE for live
updates later if needed — the AI coach already streams over SSE, so the
pattern exists. Full WebSockets not required at this scale.

### Data model sketch (new `communication` vertical slice)

- `Announcement`: id, orgId, cohortId, authorUserId, title, body (sanitized),
  pinned, createdAt.
- `DiscussionThread`: id, orgId, cohortId, contextType, contextId,
  authorUserId, title, resolved. `DiscussionPost`: threadId, authorUserId,
  body, createdAt, editedAt.
- `Conversation`: id, orgId, coachUserId, founderUserId (unique pair).
  `Message`: conversationId, senderUserId, body, readAt.
- Unread state: reuse `UserNotification`; digest email for inactive users via
  the existing ShedLock job pattern.

### Safety & governance requirements

- Sanitize all bodies with the existing OWASP sanitizer; plain-text-plus-links
  first, rich text later.
- Org/cohort scoping enforced with a guard bean (same pattern as
  `@orgAccess`/`@coachAccess`); RBAC: founders post in their own cohort only,
  coaches moderate (delete/pin) in assigned cohorts, org admins moderate
  org-wide.
- Report/flag on every post + audit log (infra exists: `AuditLog`).
- Retention policy for messages (align with the GDPR items in §6); export &
  delete on account deletion.
- Notification preferences must cover the new types (opt-out per type exists).

---

## 6. Production-Grade Requirements (cross-cutting)

### Security
- [ ] Shorten access-token TTL (24h → 15–30 min) now that refresh rotation works; access tokens are not revocable today.
- [ ] Audit anonymous read endpoints — confirm `LessonContentController` and catalog detail never return non-preview lesson bodies to anonymous users.
- [ ] Complete CSP: add the nonce pipeline for `script-src`/`style-src` (deliberately deferred in `next.config.ts`).
- [ ] Keep the `StartupSafetyValidator` pattern; add the same fail-closed check for any new secret (booking provider keys, etc.).
- [ ] Pen-test pass on the public token flows (`/a/[token]`, surveys, business cards) before scale marketing.

### Quality & CI
- [ ] Fix the 13 pre-existing lint errors and make lint **blocking** in FE CI (tracked since HANDOFF-2026-06-06).
- [ ] Raise JaCoCo floor incrementally (10% → 40%+ ratchet); 89 test files vs 874 source files is thin for this surface area.
- [ ] Add FE component/integration tests for the big `/app` feature pages (currently only 8 unit tests, all in `src/lib/`).
- [ ] Wire Playwright e2e into CI with a compose stack (BE + Postgres + MinIO + FE) — the specs exist, they just never run.
- [ ] Before flipping the courses flag: full QA pass on the player across the 12 lesson types; delete unimplemented ones.

### Observability & operations
- [ ] Error tracking (e.g. Sentry) on both ends — Prometheus/Actuator exists, but no exception aggregation.
- [ ] Alerting on the ShedLock jobs (a silently-dead `EvaluationReaper` or reminder job is invisible today).
- [ ] Uptime + synthetic checks on the public token flows.
- [ ] Backup/restore drill for Postgres + MinIO; documented RPO/RTO.
- [ ] Load test the anonymous public-assessment hot path (it's the marketing funnel).

### Compliance & data
- [ ] Data retention/deletion policy surfaced to users (retention jobs exist for logs/notifications; add account deletion/export — GDPR-relevant for EU expansion, which multi-language implies).
- [ ] Terms/privacy review for AI evaluation of user content (AI call logging already exists — good).

### SEO / PWA
- [ ] `sitemap.ts`, `robots.txt`, `manifest.json` (sw.js already present).

---

## 7. Phased Delivery Plan

### Phase 1 — Launch the core LMS + coach view
1. QA + flip the courses feature flag (S).
2. Founder dashboard aggregate page (M).
3. Coach role + `CoachAssignment` + coach console (L) — includes resolving `MANAGER`.
4. Inactivity reminder rule (S–M).
5. Cohort completion analytics endpoint + admin chart (M).
6. Cohort announcements (§5a increment 1) — cheap, high-value with the new coach console (S–M).
7. CI hardening: blocking lint, e2e in CI (S–M).

### Phase 2 — Personalization (must land well before end of Q2 2027)
1. `PillarCourseMapping` entity + admin UI (M).
2. Auto-enrollment engine on evaluation-complete events, with idempotency, admin override, and notification (L).
3. Self-paced "explore" labeling + recommendations on dashboard (S).
4. Contextual discussion threads + coach↔founder DMs (§5a increments 2–3) (M+M).
5. Mobile/PWA polish: manifest, device QA (S).
6. Start i18n for all *new* surfaces (S ongoing discipline).

### Phase 3 — Scale
1. Multi-language retrofit: BE translation tables + FE next-intl extraction (L).
2. White-label theming: branding entity + runtime tenant tokens + de-hardcoded contacts/email sender (M–L).
3. Coaching calendar: integrate Cal.com/Calendly per coach first (M); revisit native booking only if integration limits hurt (L).
4. Security/compliance items from §6 not yet done (token TTL, CSP nonce, GDPR export/delete).

---

## 8. Open Decisions (need product input)

1. **`INSTRUCTOR` vs `COACH`** — one role with authoring + coaching, or two separate roles?
2. **`MANAGER`** — delete, or wire as read-only team lead?
3. **Calendar** — integrate (fast, external dependency) vs build native (slow, owned)?
4. **i18n scope** — UI chrome only, or translated *content* (courses/lessons/pillars) too? The latter drives the data-model work.
5. **White-label depth** — colors/logo only, or custom domains + branded emails?
6. **Communications** — native lightweight build (§5a recommendation) vs external community tool (Slack/WhatsApp/Circle) as a stopgap? And should founder↔founder DMs ever be allowed, or only cohort-visible threads + coach DMs?
