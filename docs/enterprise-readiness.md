# Enterprise readiness review

Read of the working tree on branch `feature/universal-hitl-automation`, 2026-09-01. Every finding
below carries a `file:line` so it can be checked rather than believed. Nothing in this document was
inferred from the other docs — several of those are wrong, which is finding **D1**.

Build state at the time of review, both gates run:

* `mvn -o test` → **112 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS** across 19 suites.
* `npm run build --prefix frontend` → **exit 0**, 1845 modules, 408 kB bundle.
* `npm run typecheck --prefix frontend` → **exit 2, 58 errors** in five committed files. CI gates on
  this command, so **the pipeline is red on HEAD**. See **B7** and **C15**.

---

## 0. The two findings that outrank the rest

### S1 — ITSM credentials are written to the database in plaintext

[`IntegrationManagerService.java:71`](../src/main/java/com/company/mcp/service/integration/IntegrationManagerService.java#L71)
and lines 77 and 85 do this:

```java
setConfig("servicenow_password",   String.valueOf(payload.get("serviceNowPassword")));
setConfig("freshservice_api_key",  String.valueOf(payload.get("freshserviceApiKey")));
setConfig("jira_api_token",        String.valueOf(payload.get("jiraApiToken")));
```

`setConfig` (line 298) is `configRepository.save(new SystemConfig(key, value))` — a plaintext row in
`config.system_config`. They are masked on **read** (line 50, `maskSecret`), which is why this was
not obvious from the API, but the stored value is cleartext: it is in every `pg_dump`, every
replica, every backup tarball, and readable by any account with `SELECT` on that table.

This violates the project's own stated rule in three places at once —
[`SECURITY.md:39`](../SECURITY.md) ("No integration credential is stored in the database"),
[`CONTRIBUTING.md:53`](../CONTRIBUTING.md), and the PR checklist. It is also the one rule the LLM
key path *does* honour: `AiConfigService.java:` reads `${MCP_LLM_API_KEY:}` from the environment and
nothing else.

**Fix**: same pattern as the LLM key. Read the three secrets from `MCP_SERVICENOW_PASSWORD`,
`MCP_FRESHSERVICE_API_KEY`, `MCP_JIRA_API_TOKEN` via `@Value`; keep URL, username, email, JQL and
the enable flags as UI-editable rows (they are not secrets). The UI keeps its fields but renders
them read-only with "set by environment variable" — which is what the AI config page already does
for the provider key. Add a Liquibase changeset that `DELETE`s the three rows, mirroring what `1.16`
did for the provider key. Until that ships, treat any deployed database as holding live ITSM
credentials.

### S2 — `docker compose up` cannot start the backend

`application.yml:193` is `secret: ${MCP_JWT_SECRET}` — **no default**, deliberately, and
`JwtService`'s constructor throws `IllegalStateException("MCP_JWT_SECRET must be set and contain at
least 32 bytes")` when it is absent. The `mcp-app` service in `docker-compose.yml` sets no such
variable. The container starts, Spring fails the context, the container exits.

Compounding it: `SPRING_PROFILES_ACTIVE: docker` names a profile with no `application-docker.yml` in
`src/main/resources/`, so even with a secret the deployment runs on plain `application.yml` defaults
(`DB_PASSWORD:changeme`, `mcp.executor.enabled:false`).

**Fix**: add `MCP_JWT_SECRET: ${MCP_JWT_SECRET:?set a 32+ byte secret}` to the `mcp-app`
environment — the `:?` form makes compose fail loudly with a readable message instead of the
container dying on a stack trace. Either add `application-docker.yml` or drop the profile line.

---

## 1. Deployment and configuration

| # | Finding | Where | Severity |
|---|---|---|---|
| D1 | `docker-compose.yml` carries nine `MCP_AUTONOMY_*` variables for a code path deleted in commit `3e36254`. Reading them suggests unattended remediation is configurable; nothing reads them. | `docker-compose.yml` | Medium |
| D2 | `changeme` is the shipped Postgres and Redis password; `VAULT_TOKEN: dev-token`; `KEYCLOAK_ADMIN_PASSWORD: admin`. | `docker-compose.yml` | High |
| D3 | The Keycloak service still points at `mcp_db` / `mcp_user` while the app uses `incident_warden_db` / `warden_user`. Two databases, one of which nothing creates. | `docker-compose.yml` | Medium |
| D4 | `.env.example` has `POSTGRES_DB=mcp_db`, `POSTGRES_USER=mcp_user`, the dead `MCP_AUTONOMY_*` block, and **no** `MCP_JWT_SECRET`, `MCP_LLM_API_KEY` or `MCP_EXECUTOR_*`. Copying it produces a deployment that cannot boot (S2) and cannot reach a provider. | `.env.example` | High |
| D5 | `version: '3.9'` is obsolete in Compose v2 and emits a warning on every command. | `docker-compose.yml:1` | Low |
| D6 | Container runs as **root** on a **JDK** base image with no `MaxRAMPercentage` and no layered jar. In a cgroup-limited pod the JVM sizes its heap off the container limit but has no headroom cap, so an OOM kill is a matter of load, not of a bug. | `Dockerfile` | High |
| D7 | `mcp.jwt.expiry-ms: 86400000` is **dead configuration**. `AuthController.java:21-22` hardcodes `ACCESS_TTL = 30 min` and `REFRESH_TTL = 3 h`. The documented 24-hour knob does nothing. | `application.yml:194` | Medium |
| D8 | `server.tomcat.connection-timeout: -1` and `spring.mvc.async.request-timeout: -1` mean a stalled request holds a Tomcat thread forever. With `threads.max: 200`, 200 stuck LLM calls is a total outage with a healthy-looking process. Justified for a laptop demo; not for a shared deployment. | `application.yml:313,318` | High |
| D9 | `management.endpoint.health.show-details: always` exposes component detail; `/actuator/**` is ADMIN-gated so this is not anonymous, but detail includes datasource and vector-store state. | `application.yml:280` | Low |
| D10 | `tracing.sampling.probability: 1.0` traces every request. Fine locally, expensive at volume. | `application.yml:289` | Low |
| D11 | `org.springframework.ai: DEBUG` and `org.springframework.boot.actuate: DEBUG` in the default profile. Spring AI at DEBUG logs prompt and response payloads — incident text, host names, and whatever the SOP excerpt contained, into `logs/incident-warden.log`. | `application.yml:297-298` | High |
| D12 | `logging.file.name: logs/incident-warden.log` with no rotation policy. Unbounded growth on the container filesystem. | `application.yml:303` | Medium |
| D13 | `nginx.conf` sets no CSP, HSTS, `X-Frame-Options`, `X-Content-Type-Options` or `Referrer-Policy`, and no gzip. | `frontend/nginx.conf` | Medium |

## 2. Security and access control

| # | Finding | Where | Severity |
|---|---|---|---|
| A1 | **No JWT revocation.** No `jti`, no denylist, no session table. A leaked access token is valid for its full 30 minutes and a refresh token for 3 hours; disabling an account does not end its session. | `JwtService.java`, `JwtAuthFilter.java` | High |
| A2 | Access and refresh tokens live in `localStorage`, readable by any script on the origin. The mitigating control is a CSP — which D13 says is absent. | `frontend/src/services/api.ts` | Medium |
| A3 | `login()` sends a client-supplied `role` (`...(role ? { role } : {})`) — a POC role-chooser. `mcp.poc.role-selection-enabled` is `true` in `application-local.yml:37`. Verify the server ignores it in every non-local profile before this is reachable from a network. | `api.ts`, `application-local.yml:37` | High |
| A4 | `rememberMe` is accepted by `POST /api/auth/login` and **does nothing** — `REFRESH_TTL` is a flat 3-hour constant. The README documented 7 days / 1 day; that was never true of this code. | `AuthController.java:22` | Medium |
| A5 | `BootstrapPassword`: `@Value("${MCP_DEFAULT_PASSWORD:admin}")` defaults to `admin`; `alignAdmin()` logs the password in plaintext at INFO; the `random()` method at line 109 is dead code and `generated` is hardcoded `false`, so the javadoc's "a random one per process" is false. | `BootstrapPassword.java` | High |
| A6 | `baseline.sql:59-66` seeds the admin BCrypt hash **for the literal password `admin`**, with the hash and the plaintext in a comment, in the repository. `must_change_password` is `true`, which is the saving grace — but the window between first boot and first login is open to anyone who read the repo. | `db/changelog/versions/1.0/baseline.sql:59` | High |
| A7 | `csrf.disable()` is correct for a `Bearer`-token API with `setAllowCredentials(false)`, and is only correct while both hold. If a cookie is ever introduced (the documented hardening path for A2), CSRF protection has to come back in the same commit. | `SecurityConfig.java:43` | Note |
| A8 | `setAllowedHeaders(List.of("*"))` is broader than needed. `Authorization, Content-Type` covers every call `authFetch` makes. | `SecurityConfig.java:128` | Low |
| A9 | `RateLimiterService` keys a `ConcurrentHashMap<String, Deque<Instant>>` by username and IP and **never evicts the map**. Distinct keys are attacker-controlled (any username string at the login endpoint), so this is an unbounded-growth path to OOM, separate from the documented per-instance-budget ceiling. | `RateLimiterService.java` | High |
| A10 | Rate limits are per-instance in memory. Behind N replicas the effective budget is N×. Already carries a `ponytail:` note; listed because it is a deploy-time property, not a code smell. | `RateLimiterService.java` | Medium |
| A11 | `AiConfigService` loads six config values into mutable fields at `@PostConstruct`. Two replicas can hold different providers and models indefinitely after a UI change — the pod that served the write is correct, the others are stale until restart. | `AiConfigService.java` | Medium |
| A12 | `RedisConfig` is `@Profile("!local")` with **one global 30-minute TTL** for every cache. See §5 — this is a cost finding as much as a correctness one. | `RedisConfig.java` | Medium |
| A13 | The executor payload carries **no tenant id**, so one executor token is trusted for every tenant. Documented in the README as an executor responsibility; repeated here because it is the multi-tenancy boundary and it is currently outside the product. | `RemediationToolRegistry.java` | High |

## 3. Correctness, reliability, dead code

| # | Finding | Where | Severity |
|---|---|---|---|
| C1 | **`TeamController` is a fake API.** `getTeams()` returns a hardcoded "IT Ops" with `UUID.randomUUID()` — a different id on every call. `createTeam` persists nothing and returns `{"message":"Team created"}`; `addMember` returns `{"status":"success"}` and does nothing. Its only consumer is `TeamsPage.tsx`, which is no longer in the sidebar. A 200 that silently discards a write is worse than a 404. **Delete both, plus the `/api/v1/teams/**` matcher at `SecurityConfig.java:56` and the `/teams` route at `App.tsx:332`.** | `TeamController.java`, `frontend/src/pages/TeamsPage.tsx` | High |
| C2 | Three integration services each construct `private final RestTemplate restTemplate = new RestTemplate();` with **no connect or read timeout**. A ServiceNow instance that accepts a connection and never answers holds the calling thread indefinitely — and the caller is the `@Scheduled` sync (C3), so the scheduler stops running too. | `ServiceNowIntegrationService.java:19`, `FreshserviceIntegrationService.java:19`, `JiraIntegrationService.java:19` | High |
| C3 | `@Scheduled(fixedDelay = 3600000)` **exists** — and `application.yml:219-220` states in a comment that "There is no background poller: nothing in this application is `@Scheduled`". The comment is wrong. Worse, the job hardcodes `syncAllEnabled("tenant-1")`: in a multi-tenant deployment it syncs exactly one tenant, and with N replicas it runs N times with no distributed lock. | `IntegrationManagerService.java:119-127` | High |
| C4 | `NotificationService.notifyAutoRemediation` (~40 lines) has **zero callers** — a leftover of the deleted auto-run lane. Its javadoc now says so explicitly (added in this pass) so the next reader does not infer a lane that no longer exists, but the method is still a deletion candidate. | `NotificationService.java:204` | Low |
| C5 | `SearchableSelect.tsx` + `SearchableSelect.css` have zero importers. Dead. | `frontend/src/components/` | Low |
| C6 | `PAGE_META` in `App.tsx` still carries `/hitl`, `/teams`, `/incidents` entries for pages the sidebar no longer lists. | `frontend/src/App.tsx` | Low |
| C7 | `ForcePasswordReset` hardcodes "(**admin**)" as the starter password in two places, duplicating a value the server owns. When `MCP_DEFAULT_PASSWORD` is set, the UI tells the user the wrong password. | `frontend/src/App.tsx:132,144` | Medium |
| C8 | Javadoc across `IncidentPrecedentService`, `TextSimilarity` and `LocalDemoDataConfig` still explains decisions in terms of "the auto-run lane" / "the unattended lane". The classes are live and correct; the rationale they cite no longer exists, which is how the next reader reintroduces it. `NotificationService` was the fourth and is **fixed** in this pass — its class comment named the deleted lane as one of two callers and listed a third recipient source that was never implemented. | three files | Low |
| C9 | Only `HitlWorkflowService` and `AuditService` carry `@Transactional`. `IncidentService` (793 lines) and `IntegrationManagerService` do multi-row writes without one, so a partial failure mid-import leaves half a batch committed. | `service/` | Medium |
| C10 | `RagService.searchWeb()` scrapes `html.duckduckgo.com` with a spoofed Chrome user agent. Outbound egress to a third party, brittle by construction, and in many enterprises a policy violation on its own. | `RagService.java` | Medium |
| C11 | **Two operator settings have an API but no UI**, which breaks this project's own "operator settings live in the UI, not a properties file" rule. `AiConfigPage.tsx` renders exactly three cards — AI Core Engine Settings, `UserAdminPanel` ("Accounts & Access"), `IntegrationAdminPanel` — and its own header comment records that the SMTP form and the threshold sliders were deleted. But `AiConfigController` still serves `GET\|POST /api/v1/ai/config/notifications` + `/notifications/test`, and `POST /api/v1/ai/config` still accepts `hitlThreshold`. So today the notification relay and the HITL confidence band can only be changed with an authenticated `curl`. Either restore both forms or delete the endpoints — a half-removed setting is the state that makes documentation lie. | `frontend/src/pages/AiConfigPage.tsx:18`, `AiConfigController.java:88,101,109,134` | Medium |
| C12 | ~~`NotificationService.recipientsFor` javadoc lists three recipient sources; the body adds two.~~ **Fixed** in this pass — the phantom "3. the assigned group" line is gone and the comment now says why there is no third source. | `NotificationService.java:114` | None |
| C13 | **The blocking target refusals have no UI answer, so a correct refusal becomes a dead end.** `HitlWorkflowService` saves an ineligible plan as `BLOCKED` (line 183) and the `!eligible` branch returns without creating a `HitlRequest` (line 230-284). The only screen in the product carrying `targetHost` / `connectionMethod` / `targetPlatform` inputs is `HitlReviewConsole`, which is reachable only through a `HitlRequest`. So `TARGET_HOST_UNKNOWN`, `TARGET_HOST_INVALID` and `TARGET_UNREACHABLE` — the three refusals whose own escalation text says *"Enter the server this affects, then create the plan again"* — render nowhere an operator can act on, and the panel is in practice reachable only for the advisory `TARGET_REACHABILITY_UNKNOWN`. The escalation payload already returns `host.prompt()` and `PUT /api/v1/incidents/{id}` already accepts the fields, so the fix is UI-only: render the escalation's `action` string with the same three inputs on `IncidentManagementPage`, reusing the `patchIncident('target', …)` call from `HitlReviewConsole.tsx:463`. | `HitlWorkflowService.java:183,230`, `HitlReviewConsole.tsx:426`, `IncidentManagementPage.tsx:492` | High |
| C14 | ~~`HitlReviewConsole` told operators "Add users under Teams first" — a page deleted two commits ago.~~ **Fixed** in this pass; it now points at Settings → Accounts & Access. Worth noting as a class of defect: a deleted feature leaves strings behind in code, and the grep that finds them is `grep -rn 'Teams' frontend/src`. | `HitlReviewConsole.tsx:420` | None |
| C15 | **The chat page's parameter-collection card references two functions that do not exist**, so it throws `ReferenceError` the moment a user types in it. `ChatPage.tsx:909` calls `handleMissingParamChange` and line 919 calls `handleMissingParamsSubmit`; `grep -rn 'handleMissingParam' frontend/src` returns those two call sites and **no definition anywhere in the repository**. This shipped in `c8d3d9f` ("dynamic parameter collection features in chat interface"). `tsc` reports it as `TS2304: Cannot find name`, but `vite build` emits the bundle anyway, so the defect is invisible until the card renders and a user interacts with it — on the chat page, which is the product's primary surface. The other 24 `TS2339` errors in the same file are the matching half: `ToolPlan` (line 46) has no `what`, `how`, `script`, `scanLevel`, `rollback` or `mutating`, and `RunStage` (line 64) has no `label`, yet the render code reads all of them — so those panels display `undefined` rather than crashing. Either finish the feature or delete the card; a half-applied commit on the main surface is the worst of both. | `frontend/src/pages/ChatPage.tsx:909,919,46,64` | High |

## 4. Build, CI, supply chain

| # | Finding | Where | Severity |
|---|---|---|---|
| B1 | **Spring Boot 3.2.0** (Nov 2023) is past OSS support — no free security patches. Spring AI 1.0.8, Java 21. | `pom.xml` | High |
| B2 | `.github/dependabot.yml` **ignores** `spring-boot-starter-parent` and `spring-ai-bom`, which freezes the CVE floor exactly where B1 puts it. The one tool that would flag B1 is told not to. | `.github/dependabot.yml` | High |
| B3 | CI runs `mvn -B test` and a frontend typecheck+build. No `mvn verify`, no dependency-CVE scan, no SAST, no secret scan, no container build, no migration test against a real Postgres. | `.github/workflows/ci.yml` | High |
| B4 | No coverage gate (no jacoco), no `maven-enforcer-plugin` pinning the JDK, no reproducible-build flag. | `pom.xml` | Medium |
| B5 | ~~`.github/java-upgrade/**` is stale IDE scratch.~~ **Not a repo issue** — verified untracked and gitignored (`.gitignore:58-59`), so it never shipped. Deleted from the working tree; nothing to fix. | — | None |
| B7 | **The frontend CI job fails on the committed tree, so the pipeline is already red.** `npm run typecheck` is `tsc --noEmit` (`frontend/package.json:10`) and CI runs it as a gate (`ci.yml:41`). Measured on this working tree: **58 errors, exit 2**, in four committed files — 45 in `ChatPage.tsx`, 7 in `IncidentManagementPage.tsx`, 4 in `IntegrationAdminPanel.tsx`, 1 each in `LoginPage.tsx` and `ToolsPage.tsx`. `npm run build` passes (exit 0, 1845 modules) because Vite/esbuild strips types without checking them, which is exactly why this went unnoticed. Two consequences: no frontend change can be merged through a green pipeline, and a red build that everyone has learned to ignore is worse than no gate at all. Most of the 58 are `TS6133` unused imports and are one commit of deletion; the substantive ones are C15. | `frontend/package.json:10`, `.github/workflows/ci.yml:41` | High |
| B6 | Frontend has **no test script** and no test framework. The chat page is now the product surface and has zero automated coverage. | `frontend/package.json` | Medium |

## 5. Observability gaps

- **No LLM token accounting anywhere.** No counter, no meter, no per-tenant attribution. Provider
  spend is currently invisible to the platform that causes it — see §8.
- No `X-Request-Id` / correlation id propagated from the frontend, so a user-reported failure cannot
  be tied to a log line.
- Actuator exposes `prometheus`, but there is no business metric: no plans-created, no
  approvals, no dispatch outcomes, no guardrail-block counter. The audit table has the data; nothing
  surfaces it as a metric.
- No `/actuator/health/readiness` distinction wired for Kubernetes; liveness and readiness are the
  same probe, so a pod is routed traffic while Liquibase is still running.

---

## 6. Repository name

The code already answers this and the docs half-agree. `pom.xml` declares
`com.mcp:incident-warden`, `spring.application.name: incident-warden`, the log file is
`incident-warden.log`, the URL in `pom.xml` is `iamsouviki/incident-warden`, and both unstaged
controller edits rename the service string to `incident-warden`. The **directory** is still
`mcp-incident-automation` and `McpController`/`com.company.mcp` keep the old prefix.

**Recommendation: `incident-warden`.** Not because it is a better word than the alternatives, but
because five artefacts already say it and one directory does not. Renaming the directory is a
`git mv` and a GitHub setting; renaming the artifact is a release.

| Candidate | Verdict |
|---|---|
| **`incident-warden`** | **Pick this.** Already the Maven artifact, the Spring app name, the log file and the repo URL. "Warden" says gatekeeper, which is what the product is — the approval gate, not the automation. |
| `mcp-incident-automation` | Drop. "MCP" is Model Context Protocol to everyone who reads it, and `/api/v1/mcp/*` is the one part of this platform that is *not* wired up. "automation" is the claim the product deliberately refuses to make. |
| `hitl-incident-platform` | Accurate, unmemorable, and "HITL" needs expanding for every non-SRE reader. |
| `sopguard` / `runbook-warden` | Narrower than the product: SOPs are one of two evidence sources; incident history is the other. |

Consequences to land in the same change: `git mv` the working directory, rename the GitHub repo
(GitHub redirects the old path, so no link rots), and leave the `com.company.mcp` Java package
alone — a package rename touches every file for zero behaviour and would bury the review that
matters. Fix the two `serverInfo`/`service` strings (already done, unstaged) and the `McpController`
class name only if it ever stops being the MCP endpoint.

## 7. Database name

Current: database `incident_warden_db`, user `warden_user`, and **eight** schemas from
`baseline.sql:8-15` — `mcp_rag`, `auth`, `sop`, `hitl`, `incident`, `tools`, `config`, `ai`.

**Recommendation: keep `incident_warden` (drop the `_db` suffix), user `warden_app`.**

- `_db` on a database name is noise — every reader knows a connection string points at a database.
  `incident_warden_db.incident.incidents` reads worse than `incident_warden.incident.incidents`.
- `warden_user` → `warden_app`: the role is an application identity, not a person. When you later
  add `warden_ro` for BI and `warden_migrator` for Liquibase (both of which an enterprise review
  will ask for), `_app` is the one that reads as "the service".
- Environment-per-database, not schema-per-environment: `incident_warden` in each of dev/stage/prod.
  Never `incident_warden_prod` on a shared instance — the suffix invites a connection string that
  is right about the host and wrong about the data.

Two schema problems worth fixing while the names are in play:

1. **`mcp_rag` holds no RAG data.** It is the Liquibase bookkeeping schema
   (`default-schema`/`liquibase-schema` in `application.yml:38-39`); the actual vector table is
   `sop.vector_store`. Rename it `liquibase` and the layout explains itself. This is a
   `spring.liquibase` property change plus an `ALTER SCHEMA … RENAME TO` — cheap now, and a
   migration-history rewrite later.
2. **`hitl` and `ai` are created and unused.** `baseline.sql:11,15` create them, but the HITL tables
   live in `incident.*` (`incident.hitl_requests`) and AI config in `ai.ai_config` — so `ai` is
   used and `hitl` is empty. Drop `hitl`, or move `incident.hitl_requests` and
   `incident.remediation_plans` into it. Either is fine; an empty schema named after the product's
   central concept is not.

Target layout:

```
database incident_warden   owner warden_app
  liquibase    changelog bookkeeping        (was mcp_rag)
  auth         users, users_audit
  incident     incidents, plans, hitl_requests, action_executions, audit_events, telemetry
  sop          vector_store, sop_procedure
  tools        saved_scripts, execution_logs, skills
  config       system_config
  ai           ai_config
```

---

## 8. LLM memory, context, and cost

### What the platform actually spends tokens on

Five call sites, established by reading `RagService` and `RagFusionService`:

| Call | Trigger | Cost shape |
|---|---|---|
| Query expansion | one extra chat round trip, **only when direct retrieval returned fewer than `topK` documents** (`RagFusionService.java:91-97`) | ~100 output tokens + a full round trip |
| RAG answer | every chat question | the big one — see context budget below |
| Script generation | per plan, `SOP_GROUNDED` and `LLM_KNOWLEDGE` tiers | bounded by `mcp.script-gen.max-lines: 100` |
| Embedding | per SOP chunk at ingest, per query at search | cheap per call, driven by chunk count |
| Suggestions (`/analyze`) | per click on the AI Copilot card | one call |

Two things are already right and should not be "optimised" away: expansion is **conditional** (it
was unconditional and cost a measured ~7 s on every question), and `MAX_OUTPUT_TOKENS = 2048` caps
generation. Credit where due — the expensive mistake was already found and fixed.

### Where the money is: the input context, not the output

`RagService.incidentContext()` loads `findTop50ByTenantIdOrderByUpdatedAtDesc` and keeps **all 50
rows** when `rows.size() <= 40 || isAggregateQuestion(question)`. The problem is
`isAggregateQuestion`: `AGGREGATE_TERMS` contains bare single words — `which`, `next`, `more`,
`tell`, `show`, `status`, `high`, `low`, `p1`. "show me the printer issue" contains `show`.
"which host" contains `which`. **Nearly every real question is classified as aggregate**, so nearly
every request ships 50 ticket rows.

At roughly 60–90 tokens per rendered row that is **3 000–4 500 input tokens of ticket table on every
question**, plus ~1.5 KB of static instructions (duplicated verbatim between `askStrictSopRag` and
`askPublicRag`), plus up to 5 retrieved SOP chunks. The answer is a few hundred tokens. **Input
dominates output by an order of magnitude, and most of that input is rows nobody asked about.**

Second driver: `TokenTextSplitter(800, 400, 10, 10000, true)` — **400-token overlap on 800-token
chunks is 50 %**. Every SOP is embedded twice at ingest, the vector table is ~2× larger than it
needs to be, and retrieved chunks arrive with half their content repeated, so `topK: 5` delivers
closer to 2.5 chunks of distinct information at 5 chunks of token cost.

### Fixes, cheapest first

1. **Narrow `AGGREGATE_TERMS` to multi-word phrases.** Delete every bare single word; keep
   `how many`, `count of`, `by status`, `by priority`, `all open`, `list all`, `total`. This is a
   constant edit with no new machinery, and it is the single largest saving available — it moves the
   common case from 50 rows to `RELEVANT_ROW_LIMIT = 20`, and for a specific question to the handful
   of rows that actually match. **Estimated 40–60 % cut in input tokens per chat question.**
   The check that keeps it honest: one test asserting `isAggregateQuestion("show me the printer
   issue at store 42")` is `false` and `isAggregateQuestion("how many incidents are open")` is
   `true`.
2. **Drop chunk overlap from 400 to 100.** `TokenTextSplitter(800, 100, 10, 10000, true)`. Halves
   the embedding bill at ingest, shrinks `sop.vector_store`, and raises the distinct information per
   retrieved chunk. 12.5 % overlap is enough to avoid cutting a procedure mid-step.
3. **Hoist the shared prompt preamble into one constant.** ~1.5 KB is duplicated between
   `askStrictSopRag` and `askPublicRag`. One `static final String` is fewer tokens per call only if
   you also trim it — the real win is that trimming it once is then a one-line change instead of
   two divergent edits.
4. **Give `ragAnswers` its own TTL.** `RedisConfig` applies one global `entryTtl(30 min)` to every
   cache. A RAG answer keyed on tenant + session + question is valid until the underlying tickets
   change; 30 minutes throws away paid answers. Use `withCacheConfiguration("ragAnswers",
   …entryTtl(Duration.ofHours(6)))` and keep 30 minutes as the default for everything else. Highest
   ratio of saving to diff of anything in this section.
5. **Normalise the cache key.** `key = tenantId + '_' + sessionId + '_' + question` — the raw
   question string. "How many incidents are open?" and "how many incidents are open" are two
   entries. Lowercase, collapse whitespace, strip trailing punctuation. Including `sessionId` also
   means two users asking the same question in the same tenant both pay; for a tenant-scoped
   question the session is not part of the answer's identity. Dropping it from the key raises the
   hit rate substantially — but only for questions whose answer does not depend on conversation
   history, which today is all of them, because there is no conversation history (below).
6. **Model tiering.** One `ChatClient` serves every call. Query expansion (write 3 paraphrases) and
   greeting handling do not need the model that writes PowerShell for a production till. A second
   configured model name for the cheap calls is a config row and a second client, and it is the
   only structural change in this list.
7. **Meter it.** Nothing counts tokens today. Spring AI returns usage on the response; recording
   prompt/completion tokens per call per tenant into `incident.telemetry_events` — which already
   exists — turns every estimate above into a measurement. Do this **first** if you intend to argue
   about the others.

### Memory and context: there is none, and that is currently correct

Chat history lives in React state only — no `conversation` table, nothing persisted, nothing
replayed into the prompt. So there is no context window growth per session and no cost from
accumulated history.

That is the right default and the plan says so. When someone asks to reopen yesterday's thread, the
cheap version is: persist messages, and send the **last N turns plus a rolling summary**, never the
full transcript. A naive full-history replay is how a chat feature turns a fixed per-question cost
into one that grows linearly with session length — the single most common way an LLM bill goes
non-linear. Budget the context, do not just cap the messages.

### What not to do

- **Do not cache embeddings of ticket text.** Every ticket is embedded once at ingest; there is no
  repeat to save.
- **Do not add a semantic cache** (embed the question, serve a near-match). It costs an embedding
  call per question to save a chat call, and it can serve a different tenant's answer if the
  similarity threshold is wrong. Revisit only after the metering in (7) shows the exact-match hit
  rate is the bottleneck.
- **Do not lower `MAX_OUTPUT_TOKENS` below 2048.** A truncated remediation script is a worse
  failure than an expensive one.

---

## 9. Suggested order of work

**Before this is reachable from any network**

1. S1 — ITSM credentials out of the database (violates the project's own rule).
2. S2 + D4 — a deployment that boots: `MCP_JWT_SECRET` in compose, a real `.env.example`.
3. A5 + A6 + C7 — the default-password path: no plaintext INFO log, no dead `random()`, one source
   of truth for the starter password.
4. D11 — `org.springframework.ai` off DEBUG, so prompts stop landing in the log file.
5. C2 — timeouts on the three `RestTemplate`s.
6. A9 — bound the rate-limiter map.

**Before it carries production traffic**

7. D6 — non-root, JRE base, `MaxRAMPercentage`, layered jar.
8. D8 — real request timeouts.
9. A1 — token revocation, or an explicitly documented decision to accept a 30-minute window.
10. C3 — distributed lock and per-tenant iteration on the scheduled sync, or delete the scheduler.
11. B1 + B2 + B3 — supply-chain floor and a CI that would have caught it.
12. D13 — security headers at the edge.
13. C13 — render the escalation's target inputs on the incident page. Frontend-only, and until it
    lands the three blocking target refusals tell an operator to do something the UI gives them no
    way to do.
14. C15 + B7 — define or delete the two missing chat handlers, reconcile `ToolPlan`/`RunStage` with
    what the render code reads, drop the unused imports, and get `npm run typecheck` back to exit 0.
    Do this before B3 adds more gates: a red pipeline cannot enforce a new one.

**Cleanup that shrinks the review surface (do it first; it is free)**

15. C1 (fake `TeamController` + `TeamsPage`), C4, C5, C6, C8, C11. (B5, C12 and C14 are already
    done.)

**Cost, once metering exists**

16. §8 items 7 → 1 → 2 → 4 → 5.
