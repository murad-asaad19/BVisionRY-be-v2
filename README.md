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

> **Coverage:** no JaCoCo plugin is wired into `pom.xml` today, so `mvnw test` produces
> a Surefire report (`target/surefire-reports/`) but **no coverage report**. Add the
> `jacoco-maven-plugin` if a coverage gate is needed.

## Profiles

Profile is chosen via `spring.profiles.active` / `SPRING_PROFILES_ACTIVE`
(default `dev`, set in `application.properties`).

| Profile | File                            | Purpose                                                                                     |
| ------- | ------------------------------- | ------------------------------------------------------------------------------------------- |
| `dev`   | `application-dev.properties`    | Default local run. Plain-HTTP cookies (`cookies.secure=false`), Mailpit SMTP on `:1025`, dev super-admin bootstrap, Swagger enabled. |
| `local` | `application-local.properties`  | Like `dev` plus verbose SQL logging (`show-sql`, formatted, `com.bvisionry=DEBUG`).          |
| `test`  | `application-test.properties`   | Unit-test profile: H2 in-memory, Flyway off, `ddl-auto=create-drop`, simple cache.          |
| `prod`  | `application-prod.properties`   | Railway. Resend HTTP mail, `Secure` + `SameSite=None` cookies, Swagger disabled, datasource + secrets from env. |
| `mock`  | `com.bvisionry.config.mock`     | Add-on (compose uses `dev,mock`): static AI provider so evaluation runs with no live model/key. |

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
the compose `db` service restores `docker/db/bvisionry-baseline.sql`, and the LMS
migrations fold on top.

**Migrations are immutable and append-only. Never delete, edit, or renumber an
applied migration** — its checksum is recorded in every database's
`flyway_schema_history`, and changing it breaks the next migrate. The sequence has an
**intentional gap at V84** (it jumps `V83__playback_reviews.sql` → `V85__catalog_certificate_schema_fixes.sql`):
V84 was applied to environments and later removed, and that history is why the slot
stays empty. Do not fill, reuse, or renumber V84 — always add the next unused number.

## API docs (Swagger)

springdoc OpenAPI is enabled in `dev`/`local` and **disabled in `prod`**. With the app
running:

- Swagger UI — `/swagger-ui.html`
- OpenAPI JSON — `/v3/api-docs`

(On the compose API that is `http://localhost:8082/swagger-ui.html`; on a `mvnw` run,
`http://localhost:8080/swagger-ui.html`.)
