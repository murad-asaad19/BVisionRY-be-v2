# Backend — Spring Boot 4 · Java 21 · Maven

See `README.md` for run/test/profiles/migrations detail. Swagger (dev): http://localhost:8080/swagger-ui.html

## Commands

- `./mvnw test` — full suite. Testcontainers ITs need Docker; they skip cleanly without it.
- `./mvnw test -Dtest=SomeTest` — single class (prefer this loop; the full suite is slow).
- `./mvnw spring-boot:run` — dev profile on :8080 (needs the compose infra: `docker compose up -d db redis minio mailpit` from the repo root).

## Tripwires

- **Flyway migrations are immutable and append-only.** Never edit, delete, or renumber an applied `V*.sql`; always take the next unused number. The gap at V84 is intentional — do not fill it. CI rejects edits to committed migrations.
- Schema is Flyway-owned (`ddl-auto=none`): any entity change needs a new migration.
- **Tenant scoping is mandatory on new endpoints** — see "Tenant scoping" in README.md for which guard to use. Bare-ID loads (`findById`…) on org-owned repositories may only happen inside `require*` guard methods; the ArchUnit rule `bareIdLoadsOnOrgOwnedReposRequireGuard` fails the build otherwise.
- Cross-feature imports fail `ArchitectureRulesTest` unless deliberately added to the frozen-violations store (`src/test/resources/architecture/frozen-violations/`).
- After changing any DTO exposed to the web app: run `OpenApiExportTest` (writes `target/openapi.json`), then `pnpm gen:api` in `../web` — the web contract pins (`web/src/lib/contract-check.ts`) fail typecheck on drift.

## Layout for new features

Canonical for a NEW feature package: `<feature>/{domain,dto,repository,web}/` as in `workshops/` and `catalog/` (entities in `domain/`, controllers + their services in `web/`). When editing an EXISTING feature, match its current layout — three historical styles coexist (see README "Package layout").
