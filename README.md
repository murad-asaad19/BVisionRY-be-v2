# BVisionRY API (backend)

Spring Boot 4.0.5 · Java 21 · Maven. The multi-tenant assessment + LMS backend:
auth & RBAC, organizations, assessment pipelines, AI-graded evaluations
(LangChain4j over OpenRouter), surveys, the course catalog + player, certificates,
email, and public/QR flows. PostgreSQL 16 is the datastore, Redis backs caching +
rate limiting, and MinIO (S3-compatible) holds lesson media. Deploys on Railway.

## Run

```bash
# Maven — fastest local loop. Uses the `dev` profile by default; serves on :8080.
./mvnw spring-boot:run
```

`mvnw spring-boot:run` needs Postgres (and, for full functionality, Redis + MinIO +
an SMTP sink) reachable on `localhost`. The simplest way to get those is the root
Docker Compose stack: `docker compose up -d db redis minio mailpit` from the repo root.

**Compose alternative** — build + run the API in a container instead:

```bash
docker compose up -d --build api      # from the repo root; publishes host :8082 -> :8080
```

The compose `api` service runs with the **`dev,mock`** profiles (the `mock` profile
serves a static AI provider so evaluation works locally with no model key).

- Windows: `mvnw` resolves to `mvnw.cmd`; Git Bash / macOS / Linux use `./mvnw`.

## Test

```bash
./mvnw test
```

Two tiers of tests, both under `src/test/java`:

- **Unit tests** — Mockito, no infrastructure (e.g. `EvaluationServiceTest`). Where a
  Spring context is needed they run under `@ActiveProfiles("test")`, which uses an
  in-memory **H2** database (`application-test.properties`: `ddl-auto=create-drop`,
  Flyway disabled).
- **Docker-gated integration tests** — real Postgres via **Testcontainers**
  (`AbstractPostgresIntegrationTest`, e.g. `AssignmentAnswersAccessIntegrationTest`).
  Each is annotated **`@EnabledIfDockerAvailable`** (`testsupport/`), so it runs when a
  Docker daemon is reachable and is **cleanly skipped** — not silently disabled — when
  it is not. Authentication in these tests is set up via `testsupport/TestAuthentication`.

Docker Desktop is running in this environment, so the integration tests execute; on a
machine without Docker, `mvnw test` still passes (integration tests report as skipped).

> **Coverage:** `jacoco-maven-plugin` is wired into `pom.xml` — `mvnw test` produces a
> coverage report (`target/site/jacoco/`) alongside the Surefire report, and the `check`
> goal enforces a **10% line-coverage ratchet** (raise the minimum as coverage grows;
> never lower it).

## Profiles

Profile is chosen via `spring.profiles.active` / `SPRING_PROFILES_ACTIVE`
(default `dev`, set in `application.properties`).

| Profile | File                            | Purpose                                                                                     |
| ------- | ------------------------------- | ------------------------------------------------------------------------------------------- |
| `dev`   | `application-dev.properties`    | Default local run. Plain-HTTP cookies (`cookies.secure=false`), Mailpit SMTP on `:1025`, dev super-admin bootstrap, Swagger enabled, **AI transport mocked** (`bvisionry.ai.mock.enabled=true`). |
| `local` | `application-local.properties`  | Like `dev` plus verbose SQL logging (`show-sql`, formatted, `com.bvisionry=DEBUG`). AI transport mocked. |
| `test`  | `application-test.properties`   | Unit-test profile: H2 in-memory, Flyway off, `ddl-auto=create-drop`, simple cache.          |
| `prod`  | `application-prod.properties`   | Railway. Resend HTTP mail, `Secure` + `SameSite=None` cookies, Swagger disabled, datasource + secrets from env. |
| `mock`  | `application-mock.properties`   | Add-on that forces the static AI provider on ANY profile (compose uses `dev,mock`). Redundant on `dev`/`local`, which already mock. |

### The AI transport never bills from a laptop

`bvisionry.ai.mock.enabled` picks the transport, the same way
`bvisionry.mail.transport` picks the mail one. It is `false` in
`application.properties` (real provider, real spend) and `true` in `dev`,
`local` and `mock` — so **every local run is mocked by default**, with no
profile to remember. The chosen transport announces itself at boot:
`AI transport: MOCK …`.

It used to be opt-in, which meant `./mvnw spring-boot:run` — the loop this
README recommends — silently called OpenRouter for every evaluation, narrative
and cohort summary.

To exercise the live provider from a local run, for that run only:

```bash
BVISIONRY_AI_MOCK_ENABLED=false ./mvnw spring-boot:run
```

Two guards keep the switch from failing the other way, towards a production
that quietly serves canned text:

- **`prod` refuses to boot mocked.** `StartupSafetyValidator` throws if the
  `prod` profile is active with `bvisionry.ai.mock.enabled=true`. This is not
  theoretical: `application-mock.properties` is profile-specific, so it beats
  `application-prod.properties` on a `prod,mock` boot, and a stray
  `BVISIONRY_AI_MOCK_ENABLED=true` in a deploy dashboard wins outright. The
  mock answers every call successfully, so without this a mocked production
  looks entirely healthy.
- **An unreadable value picks nothing.** Both `@ConditionalOnProperty` arms are
  explicit — there is no `matchIfMissing`. An empty or non-boolean value
  matches neither, so the context fails to start rather than falling through to
  the transport that bills.

### `prod` fails closed on missing secrets

`application-prod.properties` deliberately provides **no inline fallback** for three
security-critical values — an unset value fails the boot at property resolution rather
than silently running on a committed default:

| Env var                          | Constraint                                             |
| -------------------------------- | ------------------------------------------------------ |
| `JWT_SECRET`                     | ≥ 32 bytes (HS256), must differ from the dev default   |
| `BVISIONRY_ENCRYPTION_KEY`       | exactly 64 hex chars / 32 bytes (AES-256) — **permanent** once any AI key is saved (rotating it makes stored keys undecryptable) |
| `BVISIONRY_PROXY_SHARED_SECRET`  | must match the Vercel BFF's `BFF_PROXY_SHARED_SECRET`  |

`dev`/`local`/`test` supply safe non-secret defaults for all three, so they boot with
no env configuration.

## Database migrations (Flyway)

The schema is **Flyway-owned**: `spring.jpa.hibernate.ddl-auto=none`, so Hibernate
never creates or alters tables — every schema change is a versioned migration in
`src/main/resources/db/migration` (`V1__…` … `V113__…`, ~112 files;
`baseline-on-migrate=true`). The canonical database is `bvisionry`; on a fresh data dir
the DB starts empty and Flyway builds the full schema on first boot.

**Migrations are immutable and append-only. Never delete, edit, or renumber an
applied migration** — its checksum is recorded in every database's
`flyway_schema_history`, and changing it breaks the next migrate. The sequence has an
**intentional gap at V84** (it jumps `V83__playback_reviews.sql` → `V85__catalog_certificate_schema_fixes.sql`):
V84 was applied to environments and later removed, and that history is why the slot
stays empty. Do not fill, reuse, or renumber V84 — always add the next unused number.

## Package layout

Three layouts coexist historically under `com.bvisionry.<feature>`:

| Style | Shape | Features |
| ----- | ----- | -------- |
| **DDD-ish (canonical for new features)** | `domain/ · dto/ · repository/ · web/` (controllers + their services live in `web/`) | `workshops`, `catalog`, `quiz`, `enrollment`, `programflow`, `certificate` |
| Layered | `controller/ · service/ · entity/ · repository/ · dto/` | `survey`, `pipeline`, `reporting`, `insights` |
| Flat | everything at the package root (± `dto/`, `entity/`) | `assessment`, `auth`, `organization`, `evaluation`, `lead` |

**Rule:** a brand-new feature uses the DDD-ish shape (copy `workshops/` as the
reference). When extending an existing feature, match *its* layout — do not
migrate a feature between styles as a side effect of another change.

## Tenant scoping (multi-org isolation)

Org isolation is enforced per-query; pick the guard that fits the endpoint:

1. **Path-scoped** `/api/organizations/{orgId}/**` — `OrgAccessInterceptor`
   gates automatically; nothing extra needed beyond using that path shape.
2. **SpEL-checkable param** — `@PreAuthorize("hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId)")`.
3. **Service-layer / non-path org access** — call
   `SecurityUtils.requireOrgAccess(orgId)` before touching org-owned data
   (the pattern used across catalog/quiz authoring).
4. **Loading an aggregate by bare id** — do it inside a `require*(orgId, id)`
   helper that asserts ownership (`requireWorkshop`, `requireAssignmentInOrg`, …).
   This is machine-enforced: the ArchUnit rule
   `bareIdLoadsOnOrgOwnedReposRequireGuard` fails the build on a bare-ID
   `findById`/`findAll` against an org-owned repository outside a `require*`
   method.

All four routes end at the same predicate (`OrgAccessGuard.callerHasAccess`):
SUPER_ADMIN, member of the org, or ORG_ADMIN of its parent org.

## API docs (Swagger)

springdoc OpenAPI is enabled in `dev`/`local` and **disabled in `prod`**. With the app
running:

- Swagger UI — `/swagger-ui.html`
- OpenAPI JSON — `/v3/api-docs`

(On the compose API that is `http://localhost:8082/swagger-ui.html`; on a `mvnw` run,
`http://localhost:8080/swagger-ui.html`.)
