# AI Evaluation Engine — Migration to LangChain4j

**Status:** implemented on `feat/ai-engine-rebuild` (backend) + `feat/needs-review-ui` (web). Full backend suite green; live-verified against the dockerized dev stack.

This document summarizes the technical decisions and the runtime behavior changes from replacing the Spring AI / Anthropic-SDK inference path with a model-agnostic engine built on LangChain4j over OpenRouter's OpenAI-compatible API.

---

## 1. Why we switched

The goal was a **model-agnostic pipeline** — the engine must produce correct, well-formed results regardless of which model is configured, and degrade safely when a model returns uncertain output (malformed JSON, truncation, refusals, out-of-range scores).

The previous design pointed the **Anthropic Java SDK at OpenRouter's URL**. That coupled the whole pipeline to one model family's response shape, options, and usage object — the opposite of model-agnostic. Robustness rested entirely on ad-hoc string scraping (`extractJson`) + a bare `ObjectMapper` + a manual validator, with **no repair loop**, **no fail-loud semantics**, and a retry config (`spring.ai.retry.*`) that never reached the native SDK path.

---

## 2. Architectural decisions

| Decision | Before | After | Rationale |
|---|---|---|---|
| **Transport** | Anthropic SDK aimed at `openrouter.ai/api` (Anthropic Messages wire format) | LangChain4j `OpenAiChatModel` against `https://openrouter.ai/api/v1` (OpenAI-compatible) | One uniform request/response/usage/error shape for **every** model OpenRouter hosts. This is the keystone of model-agnosticism. |
| **Capability detection** | None (assumed Anthropic features) | `ModelCapabilityRegistry` reads each model's `supported_parameters` **live** from OpenRouter `GET /models` | The engine adapts its request strategy to what the configured model actually supports — no hard-coded per-model table. |
| **Structured output** | Prompt-instructed JSON only | Capability-gated **native strict JSON schema** when the model supports `structured_outputs`; prompt-instructed JSON otherwise | Provider-enforced shape where possible (fewer repair round-trips, higher accuracy); universal fallback everywhere else. |
| **Repair loop** | None — a parse/validation failure became a silent `null` | LangChain4j `AiServices` + `@OutputGuardrails` / `outputGuardrails(...)` with `OutputGuardrailsConfig.maxRetries` | On invalid output the model is **reprompted with the specific error** and retried (default 2). Framework-native, not hand-rolled. |
| **Resilience** | Bounded HTTP timeouts only; dead `spring.ai.retry` | Resilience4j **circuit breaker + bulkhead** (used programmatically, not via the Spring Boot starter — the project is on **Spring Boot 4.0.5**) | Fast-fail during a provider outage instead of every submission exhausting retries. Content (guardrail) failures are **ignored** by the breaker — they're not a provider outage. |
| **Confidence / accuracy** | Single pass | **Confidence-gated self-consistency**: a borderline score (near a maturity threshold) triggers extra samples, then the median is taken | Spends extra accuracy budget only where a one-or-two-point wobble would flip the maturity label. Model-agnostic (no model-specific confidence field). |
| **Fail-loud** | Failures persisted as zero-score `EVALUATED` rows | New `NEEDS_REVIEW` submission status + `pillar_evaluations.ai_failed` | A parse/validation failure is never disguised as a real result. |
| **Model trust** | Hope | `ModelEvalHarness` golden-set runner (in-band accuracy + schema-valid rate + latency) | Swapping a model becomes a *measured* operation. |
| **Observability** | Token/cache counts via a hard cast to the Anthropic SDK `Usage` | Token usage read provider-uniformly from the LangChain4j `Result` | No provider-specific cast; cache-read tokens come from the OpenAI-compatible usage extension. |

### New components (`com.bvisionry.aiengine.*`)
- `transport/` — `Lc4jChatModelProvider`, `ModelCapabilities`, `ModelCapabilityRegistry`
- `guardrail/` — `StructuredOutputGuardrail`, `JsonExtraction`
- `service/` — `AiEvaluationEngine`, `PillarEvaluator`, `SummaryGenerator`, `TeamInsightGenerator`
- `resilience/` — `AiResilience`
- `confidence/` — `ConfidenceGate`
- `eval/` — `ModelEvalHarness`, `GoldenCase`, `ModelEvalReport`
- `mock/` — `MockLangChainChatModel`

### What we deliberately kept
Async orchestration (`EvaluationService.evaluateSubmissionAsync` + `AfterCommit` + the executor pools), parallel per-pillar fan-out, DB entities + provenance columns, the `ai_call_logs` audit trail, admin-editable prompts (`PromptTemplateService`), `ScoringService` thresholds, and the `mock`/`e2e` test profiles (re-pointed to the LangChain4j seam). `OpenRouterChatService` kept its public API — its internals now delegate to `AiEvaluationEngine`, so `EvaluationEngine` and `InsightService` callers were unaffected.

---

## 3. Behavior changes (runtime)

### 3.1 Uncertain output is now repaired, not silently zeroed
This is the most important change.

| Model output | Before | After |
|---|---|---|
| Malformed / truncated JSON | `extractJson` fails → `null` → pillar saved with **score 0**, submission `EVALUATED` | Guardrail reprompts the model (up to `repair-retries`); recovers if a retry is valid |
| Prose / refusal, no JSON | same silent zero | reprompt → recover, else fail loud (below) |
| Missing `scorePercentage` | primitive `int` defaulted to **0**, accepted as valid | guardrail detects the missing field and reprompts |
| Out-of-range score (e.g. 150) | validator threw, caught as a parse failure → silent zero | guardrail reprompts to correct it |
| JSON wrapped in trailing prose with `{...}` | `lastIndexOf('}')` mis-extracted → parse failure | balanced-brace scanner extracts the correct object |

### 3.2 Fail-loud: the `NEEDS_REVIEW` status
When a pillar or the overall summary still can't be evaluated **after** the repair retries:
- The submission is marked **`NEEDS_REVIEW`** (not `EVALUATED`). Partial results are still saved and viewable.
- The affected pillars carry `pillar_evaluations.ai_failed = true`.
- The completion **email is suppressed** (we don't notify the member of incomplete results).
- A `bvisionry.ai.evaluation_degraded` metric is incremented and the reason is stored on `failureReason`.
- **Admins can retry** a `NEEDS_REVIEW` submission (previously retry was allowed only for `FAILED`).

### 3.3 Team insights fail loud + are retryable
- A team-insight parse failure now marks the report **`FAILED`** (was: `COMPLETED` with an unusable `{rawResponse}` blob).
- New endpoint **`POST /api/organizations/{orgId}/org-insights/{reportId}/retry`** re-runs a `FAILED` report.

### 3.4 Transport retries and circuit breaking actually work now
- Transient HTTP errors are retried by the LangChain4j client's `maxRetries` (the old `spring.ai.retry.*` never applied to the native Anthropic SDK path).
- A sustained provider outage opens the circuit (`AiResilience`) and subsequent calls fast-fail instead of each submission burning its full retry budget.

### 3.5 Provider routing
- **All inference goes through OpenRouter's OpenAI-compatible endpoint.** Claude models are still available — via OpenRouter model IDs (e.g. `anthropic/claude-sonnet-4`).
- The `AIProvider` enum (`OPENROUTER` / `ANTHROPIC`) and the dual encrypted key slots are retained in the DB, but the engine routes OpenRouter-only. The direct-Anthropic inference path was removed. (Collapsing the enum is an optional future cleanup.)

### 3.6 Token / cache accounting
- Input/output tokens are read provider-uniformly.
- Cache accounting changed: OpenAI-compatible usage reports **cached (read) tokens** only, so `cacheReadTokens` is populated and `cacheCreationTokens` is now always null. Anthropic's `SYSTEM_ONLY` cache strategy (which, per prior analysis, never engaged because the cached prefix was under the ~1024-token floor) is gone; prompt caching is now whatever the OpenRouter route provides automatically.

### 3.7 Accuracy: borderline scores
Pillars whose score lands within a small margin of a maturity-band boundary are re-sampled and the median is used. This makes borderline classifications more stable, at the cost of a few extra calls **for those pillars only** (gated; mid-band scores are unaffected).

---

## 4. Schema / data changes

**Migration `V96__needs_review_status_and_ai_failed.sql`:**
- Extends the `submissions_status_check` constraint to allow `NEEDS_REVIEW`.
- Adds `pillar_evaluations.ai_failed BOOLEAN NOT NULL DEFAULT FALSE`.

Both changes are **additive and backward-compatible** — a prior (pre-V96) app instance keeps working against a V96 DB while running.

**Migration `V97__normalize_ai_provider_to_openrouter.sql`** normalizes any existing `ai_configurations.provider = 'ANTHROPIC'` rows to `'OPENROUTER'` (the Anthropic key slot is preserved) so the engine — which routes all inference through OpenRouter — can never resolve the wrong key. New configs default to `OPENROUTER`. See §11.

---

## 5. API changes

- **New:** `POST /api/organizations/{orgId}/org-insights/{reportId}/retry` — retry a FAILED insight report.
- **`PillarDetailResponse`** gains `aiFailed` (boolean) so the FE can flag pillars the AI couldn't evaluate.
- The member results endpoint already served any non-`IN_PROGRESS`/`SUBMITTED` submission, so it returns **partial results for `NEEDS_REVIEW`** with no change.

---

## 6. Configuration changes

**Removed** (vestigial — the engine no longer uses Spring AI auto-config):
```
spring.ai.openai.api-key / base-url / chat.options.model
spring.ai.retry.max-attempts / on-http-codes
```
Provider, API key, model names, temperature, and max-tokens continue to live in the DB (`ai_configurations`, managed from the admin panel).

**Added** under `bvisionry.ai.*` (all have safe in-code defaults):
```
bvisionry.ai.repair-retries                 # guardrail reprompt budget (2)
bvisionry.ai.max-retries                    # transient-HTTP retries (2)
bvisionry.ai.cb.*                           # circuit breaker tuning
bvisionry.ai.bulkhead.max-concurrent        # concurrent-call cap (24)
bvisionry.ai.bulkhead.max-wait-millis        # brief queue window before rejecting (3000)
bvisionry.ai.escalation.*                   # confidence-gated self-consistency
bvisionry.ai.openrouter.referer/app-title   # optional OpenRouter ranking headers
bvisionry.ai.capabilities.cache-ttl-minutes # capability-registry cache TTL (60)
```

---

## 7. Dependency changes (`pom.xml`)

**Removed:** `spring-ai-bom`, `spring-ai-starter-model-anthropic` (and the `spring-ai.version` property).
**Added:** `langchain4j-bom` (1.11.8) → `langchain4j` + `langchain4j-open-ai`; `resilience4j-circuitbreaker` + `resilience4j-bulkhead` (2.2.0, used programmatically).

The dead Spring-AI files were deleted: `AIChatModelFactory`, `config/mock/MockChatModel`, `e2e/FakeChatModel`. Mocks were re-pointed to the LangChain4j seam (`MockLangChainChatModel`, `FakeLangChainChatModel`).

---

## 8. Frontend changes (`feat/needs-review-ui`)

`NEEDS_REVIEW` is handled across the UI: the `SubmissionStatus` union + all status label/variant maps (member + admin), an "under review" banner on the results page, admin retry covering `NEEDS_REVIEW`, a retry button for `FAILED` insight reports, read-only taker, history/list inclusion, and a per-pillar "could not be analysed" note from `aiFailed`. Typechecks, lints, and `next build` clean.

---

## 9. Operational notes

- **Dev DB:** the live verification applied `V96` to the shared dev database. Once `main` carries this branch, rebuilding the backend image is consistent. (A pre-merge restart of an old image would hit Flyway's "applied migration not resolved locally" check — merge first, or set `spring.flyway.ignore-migration-patterns=*:missing`.)
- **API key:** the `OPENROUTER_API_KEY` env var is no longer read directly by the app; the key is stored encrypted in `ai_configurations` and set via the admin panel.
- **Verification:** full backend test suite (incl. Testcontainers) green; the rebuilt app was booted live (mock profile) against the real dockerized Postgres + Redis with `/actuator/health` UP and `V96` confirmed.

---

## 10. Known follow-ups
- `AIProvider` enum still includes `ANTHROPIC`, but it is now **inert for inference**: the default is `OPENROUTER`, existing rows are normalized by V97, and the transport always resolves the OpenRouter key (see §11). Fully removing the enum value is optional cleanup.
- `ModelEvalHarness` ships with a small built-in golden set — externalize/expand it per deployment to benchmark candidate models.
- The capability-aware native-structured-output path depends on OpenRouter's `supported_parameters`; models with no metadata fall back to the (universal) prompt + guardrail path.

---

## 11. Post-review hardening

An independent expert review surfaced findings that were fixed before the PR (each verified by a second independent reviewer; full suite green):

- **Provider/key routing (was critical):** the config defaulted to `ANTHROPIC` and the key resolver returned the Anthropic slot, while the transport always sends an OpenRouter Bearer token — so a default or `ANTHROPIC` config would 401. **Fixed:** default is now `OPENROUTER`, migration **V97** normalizes existing rows, and a dedicated `AIConfigService.getDecryptedOpenRouterApiKey()` (used by `Lc4jChatModelProvider` and `ModelCapabilityRegistry`) always reads the OpenRouter slot. `AIModelCatalogService` keeps its per-provider key by design (its upstream `/models` call legitimately branches per provider).
- **Confidence escalation was serial + blocking:** re-samples now run **in parallel** on a dedicated bounded `escalationExecutor` (separate from the pillar pool to avoid starvation), gated to borderline pillars and capped.
- **Bulkhead could manufacture false `NEEDS_REVIEW`:** under local pillar fan-out the zero-wait bulkhead rejected healthy calls as if the provider were down. **Fixed** with a short queue window (`bvisionry.ai.bulkhead.max-wait-millis`, default 3000ms) and a higher concurrency cap (24, above the pillar pool + escalation budget).
- **Pillar guardrail only checked the score:** it now also requires the narrative fields (`whatThisScoreMeans`, `whatsWorking`, `whatCanImprove`), so a score-only response is repaired rather than accepted with empty defaults.
- **Escalation audit fidelity:** discarded re-samples are tagged `[escalation-sample-N]` in `ai_call_logs` so token accounting and provenance stay honest.
