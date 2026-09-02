# Contributing

Thanks for looking. This project is a human-in-the-loop incident automation platform, which means a
bad patch can end with a script running on someone's production server. The rules below exist for
that reason and not for ceremony.

## Getting it running

You need **JDK 21**, **Maven**, **Node 20+**, and **PostgreSQL 16 with the `vector` extension** on
`localhost:5432` (database `incident_warden_db`, user `warden_user`). Postgres + pgvector is the only supported
store — the old H2 + fake vector store were removed because a fake `similaritySearch` returned every
document unranked and made retrieval look like it worked while proving nothing.

```bash
mvn -o spring-boot:run -Dspring-boot.run.profiles=local -Dmaven.test.skip=true
```

```bash
npm run dev --prefix frontend
```

Then <http://localhost:5173>, sign in as `admin` / `admin` — the username is the starter password
and the first screen after login is a forced password change. The two dev stand-ins
(`scripts/dev-executor.mjs`, `scripts/dev-smtp.mjs`) make the execution and notification legs
observable without anything leaving your machine — see [Quick start](README.md#quick-start).

## Before you open a PR

```bash
mvn -o test
```

```bash
npx tsc --noEmit --project frontend
```

Both must pass — **112 tests, 0 failures** is the current baseline. `mvn -q` hides the test summary,
so if you use it, read `target/surefire-reports/*.txt` instead of trusting a silent exit code.

Migrations are one squashed changeset (`1.0-baseline`). **Never edit an existing changeset** — add a
new one. A changed checksum stops every database that already ran it.

## Things that must not break

If your change touches the remediation path, re-read [the invariants](README.md#the-invariants)
first. In particular, a PR will not be merged if it:

- introduces `ProcessBuilder`, `Runtime.exec`, or an SSH/WinRM client into this application — the
  control plane dispatches to an executor agent and does not run commands itself;
- lets a script run without a matching approved SHA-256 hash;
- skips the guardrail re-scan at dispatch;
- adds a path where a mutating action runs on a host the platform inferred rather than confirmed;
- adds any way for a script to be dispatched without a person approving that exact script — a
  scheduler, a confidence threshold, an inherited approval from an earlier incident. That path
  existed once and was deleted on purpose; re-adding it is the one change that changes what this
  product *is*;
- stores an integration credential, provider API key, or token in the database, or returns one from
  an API. (`IntegrationManagerService` currently breaks this rule and is a known defect — see
  [S1](docs/enterprise-readiness.md). Fixing it is welcome; matching it is not.);
- adds a configuration knob that can only be set by editing a properties file when it is something
  an operator needs to change at runtime. Operator-facing settings belong in the UI.

## Code style

Match the file you are editing. Two habits are consistent across this codebase and worth keeping:

- **Comments explain why, not what.** The interesting comments here record the failure that caused
  the code to look the way it does — a cached provider timeout that broke a question permanently, a
  real Kafka ticket refused as out of scope. That is the part a future reader cannot recover from the
  diff.
- **A deliberate shortcut is labelled.** Where something cuts a real corner with a known ceiling, it
  carries a `ponytail:` comment naming the ceiling and the upgrade path — for example the in-memory
  rate limiter noting that the deques move to Redis if this is ever load-balanced. Add the note
  rather than the abstraction.

Non-trivial logic needs one test that fails if the logic breaks. Not a suite — one test. The scope
gate, the cache-exclusion predicate and the guardrail matcher are each covered by exactly one, and
that is the bar.

## Where the useful gaps are

[docs/enterprise-readiness.md](docs/enterprise-readiness.md) is the full list with `file:line`
references and a suggested order; [Known gaps](README.md#known-gaps) is the short version. The ones
most worth a contribution:

- **Get `npm run typecheck` back to green.** It exits 2 today with **58 errors** in five committed
  files, so the frontend CI job is red before you touch anything — if your first run fails, it is not
  you. Roughly 40 are unused imports (`TS6133`) and are pure deletion. The two that matter: the chat
  page calls `handleMissingParamChange` and `handleMissingParamsSubmit` (`ChatPage.tsx:909,919`) and
  **neither function exists**, so that card throws a `ReferenceError`; and `ToolPlan`/`RunStage`
  (lines 46, 64) are missing six fields the render code reads. Good first contribution, and nothing
  else can be gated until it lands.
- **Answering a target refusal from the UI.** `TARGET_HOST_UNKNOWN` blocks a plan and says "enter the
  server this affects" — and the only screen with a hostname input is the HITL review console, which
  a blocked plan never reaches, because the `!eligible` branch returns without creating a request.
  Render the escalation's `action` string plus the three inputs on `IncidentManagementPage`, reusing
  `patchIncident('target', …)` from `HitlReviewConsole.tsx:463`. Frontend-only.
- **ITSM credentials out of the database.** `IntegrationManagerService` writes three secrets to
  `config.system_config` in plaintext. The fix pattern already exists in `AiConfigService`: read them
  from the environment, keep the non-secret fields UI-editable, add a changeset that deletes the
  rows. Highest-value fix in the repository.
- **A deployment that boots.** `docker compose up` fails because `mcp-app` sets no `MCP_JWT_SECRET`,
  and `SPRING_PROFILES_ACTIVE: docker` names a profile with no `application-docker.yml`.
- **A real executor agent.** `scripts/dev-executor.mjs` runs nothing on purpose. A sandboxed agent
  that does run scripts, with its own allowlist and audit log, is the highest-value missing piece.
- **MCP tool access.** The registry and `/api/v1/mcp/*` exist; nothing wires the agent to actually
  call an MCP server yet.
- **A distributed lock on `IntegrationManagerService.scheduledSync`**, which currently runs on every
  replica.
- **Timeouts on the three ITSM `RestTemplate`s**, none of which has one.
- **A frontend test suite.** There is no `test` script and no framework, and the chat page is the
  product.
- **The two API-only settings.** The notification relay (`/api/v1/ai/config/notifications`) and the
  HITL band (`hitlThreshold` on `POST /api/v1/ai/config`) have live endpoints and no UI — the forms
  were removed from `AiConfigPage.tsx` and never replaced. Either restore both cards or delete the
  endpoints; a half-removed setting is what makes documentation lie. Small, self-contained, and it
  closes a violation of the rule two bullets up.
- **Deleting `TeamController` and `TeamsPage.tsx`** — a fake API that returns `200 "success"` and
  persists nothing, plus its only consumer.

## Licensing of contributions

By opening a pull request you agree your contribution is licensed under
[Apache 2.0](LICENSE), same as the rest of the project. There is no CLA.

Be decent to each other in issues and reviews. That is the whole code of conduct.
