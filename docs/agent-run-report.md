# Agent run report — live state

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

## 1. Resume protocol — do these in order

A fresh agent starts here. Nothing below assumes prior context.

```bash
# 0. Read the constitution and the design. Not the roadmap narrative.
#    backend/docs/agent-policy.yml
#    backend/docs/agent-execution-graph.md

# 1. Read the decision log: backend/docs/agent-decisions.md (on the base branch).

# 2. Create the worktree PAIR for the lane you claim (both repos, matching
#    branch names — an agent holding one repo is broken undetectably).
#    Branch naming: agent/<ticket-id>-lane<n>, off the base branches in §2.
git -C backend worktree add ../.agent-wt/lane<n>/backend -b agent/<ticket>-lane<n> <backend-base>
git -C web     worktree add ../.agent-wt/lane<n>/web     -b agent/<ticket>-lane<n> <web-base>

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

| Repo | Base branch | Agent branches |
|---|---|---|
| `backend` | `claude/production-roadmap-requirements-xp8zsf` | none — fresh |
| `web` | `staging` | none — fresh |

The backend base is docs-ahead of `staging` only (no code drift), so both repos
have code-identical bases — `pnpm gen:api` output on `staging` is valid for
either. No sandbox lanes are running.

---

## 3. What has landed — and what is only *claimed*

Nothing. No ticket has been attempted in this run.

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
| Error tracking (regression signal) | `error_tracking` | ❌ not started | — |
| e2e green locally (FE evidence) | `e2e_local_green` | ❌ not started | — |
| Diff coverage + blocking lint | `diff_coverage_and_lint_gate` | ❌ not started | — |
| Scope manifest gate | `scope_manifest_gate` | ❌ not started | — |

**Phase 1 is blocked until all six are ✅.** No exceptions.

---

## 5. Next actions

Phase 0 runs **two-wide**, not four. Four of its five tickets are zone
`platform` and share `pom.xml` / `package.json` / `.github/workflows/`, and
`scheduler_rules` forbids two active tickets in one zone regardless of free
lanes. Two idle lanes here is the design working, not a scheduling failure.

| Order | Ticket | Zone | Lane | Notes |
|---|---|---|---|---|
| 1 | `error_tracking` | platform | 1 | First ticket of the run |
| 1 (parallel) | `gdpr_export_delete` | auth | 2 | Own worktree pair `.agent-wt/lane2/` |
| 2 | `e2e_local_green` | platform | 1 | Same pair as `error_tracking` — stacks on its commit |
| 3 | `diff_coverage_and_lint_gate` | platform | 1 | Serial — same zone |
| 4 | `scope_manifest_gate` | platform | 1 | Encodes the scope rule as an actual check |

Gate 4 needs **two live processes per lane** — backend on the lane's
`SERVER_PORT` and `pnpm dev` on its web port — with `E2E_BASE_URL` taken from the
lane env and never overridden. `playwright.config` has no `webServer` by design.
Run both under Monitor.

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

1. **Validator context purity is procedural, not enforced.** Validators must
   never see the implementer's reasoning; when an orchestrating agent relays
   between them it is itself the leak. Make it mechanical once
   `scope_manifest_gate` lands: drive validators from a script whose prompt is
   `git diff` + ticket spec + `agent-policy.yml` and nothing else.
2. **Gate 4 has never passed.** Until `e2e_local_green` is green, every
   frontend-touching ticket is landing on backend evidence alone, against a
   frontend with ~2% unit coverage.
