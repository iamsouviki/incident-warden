# Automated Incident Remediation with HITL — architecture & implementation plan

**Scope note.** This is not a greenfield design. The three-stage workflow (extract → analyse →
execute), the HITL approval gate, the three-source ITSM ingest, the RAG chat, the guardrails and the
review-and-run UI **already exist in this repository**. This document is the delta: what is built,
the eight things that are genuinely missing, three requirements that should be cut, and how it all
fits in $45.

**Status, 2026-09-01.** Everything in §2 has since been built, on free components only — the
budget conversation in §4 resolved to Ollama-local at $0. Each gap below carries what actually
shipped, including the two places the implementation deliberately departed from this plan (G1's
call site, and the knowledge graph in §3, which was cut here and then built on Postgres).

Hardening backlog (credentials, deployment, supply chain) is not repeated here — it lives in
[enterprise-readiness.md §9](enterprise-readiness.md#9-suggested-order-of-work) and outranks
everything below.

---

## 1. What already exists

| Requirement | Status | Where |
|---|---|---|
| Multi-source ingest: ServiceNow, Freshservice, Jira | **built** | `service/integration/*IntegrationService.java`, `IntegrationManagerService.syncAllEnabled()` |
| Async CSV/XLSX import | **built** | `IncidentIntakeService.java:61-104` (`.csv` + `.xlsx`) |
| RAG over ingested incidents | **built** | `RagService.java`, `RagFusionService.java`, `POST /api/v1/rag/chat` |
| Scenario A — analysis + tool list + Run/Cancel | **built** | `ChatPage.tsx:1174-1229` (`renderPlanCard`) |
| Scenario A — Review & Run popup w/ script, steps, rollback, plan hash | **built** | `ChatPage.tsx:1544-1650` |
| Scenario A — animated execution state, per-stage spinners, streamed log reveal | **built** | `ChatPage.tsx:886-956`, `renderRun` at `:1231` |
| Scenario A — dynamic parameter collection when the incident lacks inputs | **built** | `ChatPage.tsx:661-731`, `1086-1172` |
| Scenario B/C — SOP-vs-model routing, RCA prose | **built (backend)** | `IncidentService.suggestResolution()` |
| Conversational guardrails (refuse personal/off-topic) | **built** | `RagService.refuse()` + `OUT_OF_SCOPE_MESSAGE`, `GuardrailService.java` |
| PII/secret masking with `****` | **built, logs included** | `PublicReadService.maskSensitive()` + `MaskingConverter`/`MaskingThrowableConverter` via `logback-spring.xml` |
| Confidence removed from UI and schema | **built (deleted)** | nothing left in `frontend/src` or the model; see G4 |
| Metrics/actuator/Prometheus | **built** | `application.yml:283-301` |
| Distributed tracing | **built** | `micrometer-tracing-bridge-brave` (`pom.xml:221`) → `%X{traceId}` in every log line |
| Knowledge graph | **built** | `db/changelog/versions/1.2/incident_graph.sql`, `IncidentGraphService`, `GET /api/v1/incidents/{id}/graph`, Relationships tab |
| `npm run typecheck` | **green (0 errors)** | README's "58 errors" note is stale |

The routing already implements the three scenarios. `AgentAssessmentService.assess():94-97`:

```java
String route = evidence.approvedEvidencePresent() && !classification.action().isBlank()
               && score >= threshold ? "HITL_REQUIRED" : "ESCALATE";
```

SOP + tool → Scenario A. Anything else → escalate. Scenario B/C are then separated downstream by
`suggestResolution()` asking the same question again.

---

## 2. The eight real gaps

Ordered by value. Sizes are working estimates for the diff, not the review.

### G1 — Resolution prompt after a successful run *(Scenario A, the missing tail)*

`runPlan`'s `finish()` (`ChatPage.tsx:926-930`) sets `done: true` and stops. The requirement is:
"The issue appears resolved. Please verify. Would you like to update the incident status?" → OK /
Cancel → resolve in the source system → success message.

Backend already has the write-back and **nobody calls it**:
`IntegrationManagerService.updateExternalStatus()` (`:186-213`) dispatches to
ServiceNow/Freshservice/Jira `updateStatus()`. Zero callers.

**Shipped — frontend only, and not where this plan said.** The plan proposed wiring
`updateExternalStatus()` into `IncidentService.updateIncident()` so every status write pushed
outward from one call site. That was wrong: HITL sets status internally (`PENDING_APPROVAL`,
`ESCALATED`) on the same method, so the vendor would receive a webhook-load of internal workflow
states it has no column for.

`POST /api/v1/integrations/incidents/{id}/status` (`IntegrationController:59-70`) already pushes to
the source system *and* saves locally, and it is reached only when a human asks for it. So the
frontend calls that: `Message.resolve` is set by `runPlan`'s `finish()` on a successful run only,
renders the requested copy with OK/Cancel, and OK posts `{status:'Resolved'}`. The card reports the
endpoint's `updated` flag honestly — a local save with no vendor confirmation says so rather than
claiming the ticket is closed.

### G2 — Collapsible log viewer

**Shipped** as `<details className="chat-log-wrap" open={isRunning}>` in `renderRun`, with
`list-style: revert` so the native disclosure triangle survives the app's list reset.

`.chat-log` (`ChatPage.css:529`) is a permanently-open scroll box. Wrap the existing
`stage.log.join('\n')` in `<details><summary>` — native element, keyboard-accessible and
screen-reader-labelled for free, no state, no library. Open by default while `state === 'running'`,
collapsed once the stage succeeds. ~8 lines.

### G3 — Mask the log files

**Shipped**, both steps, plus a third the plan missed: `%maskEx`. `MaskingConverter` covers `%msg`;
Logback renders throwables through a separate converter, so without `MaskingThrowableConverter` a
masked file still leaked the credential inside a `JdbcSQLException`'s connection URL. Frame lines
are exempt — masking them turned `org.hibernate.tool.schema.internal` into `...tool.****.internal`
and cost every stack trace its readability to redact nothing. `MaskingConverterTest` pins both.

`maskSensitive()` already exists and is regex-complete (passwords, tokens, bearer, basic-auth URLs,
email, IPv4/IPv6, internal hostnames, PAN, phone, SSN). It is applied to API responses only, so
`logs/incident-warden.log` still receives raw prompts — incident text, hostnames, SOP excerpts —
because `application.yml:308` sets `org.springframework.ai: DEBUG`.

Two steps, in this order:

1. **`org.springframework.ai: WARN`.** One line. Removes the bulk of the exposure immediately.
2. A Logback `ClassicConverter` that calls the existing `maskSensitive()`, registered as
   `%mask(%msg)` in the pattern. Requires adding `logback-spring.xml` (there is none — the
   `logging.pattern.*` keys in `application.yml` are Boot defaults). ~30 lines + one config file.

Do not write a second masking regex. There is one, it is tested (`PublicReadServiceTest`), and two
copies will drift.

### G4 — Delete confidence *(and the scoring that feeds it)*

**Shipped**, whole list, net deletion. `grep -ri confidence frontend/src` is empty and the route is
now exactly the rule below. Behavioural consequence, accepted: a P1 with an approved SOP and a known
tool routes to `HITL_REQUIRED` instead of being escalated away from the reviewer by a score. Its risk
still reaches them as `plan.riskScore`, and a human still clicks approve.

The requirement is removal from the DB schema and the UI. Taking it seriously deletes more than the
column, because with the autonomy surface already gone (README: "The autonomy surface is deleted,
not disabled") confidence gates exactly one thing: whether a plan reaches a human or is escalated
away from one.

Drop the score from `AgentAssessmentService` and the route becomes:

```java
String route = evidence.approvedEvidencePresent() && !classification.action().isBlank()
               ? "HITL_REQUIRED" : "ESCALATE";
```

— which is literally "SOP available AND tool available → Scenario A". The requested decision rule,
with the six-factor weighted sum (`patternSimilarity`, `historicalSuccess`, `sopReliability`,
`systemHealth`, `riskPenalty`, `precedentSimilarity`) deleted. Fewer plans get silently escalated,
and every one still needs a human click.

Removal list:
- `baseline.sql:116` (`incident.confidence_score`), `:150` (`remediation_plan.confidence_score`) — new
  Liquibase changeset with two `DROP COLUMN`s
- `Incident.java:58,116,194,266,285` · `RemediationPlan.java:18,42`
- `HitlWorkflowService.java:188,210,248,257,274-276,287,383` — including the
  `CONFIDENCE_BELOW_HITL_BAND` refusal reason
- `AgentAssessmentService.java:28-33,62-64,94-97` + the scoring body
- `IncidentService.java:106-113` (`calculateConfidenceScore`)
- `AiConfigService` `hitlThreshold` + `AiConfigController.java:51,88`
- `mcp.confidence.*` in `application.yml:155`, `application-local.yml:52`
- `HitlApprovalQueue.tsx:18,136,171-173` + `.css:27-29`, `HitlReviewConsole.tsx:34,377`
- `McpController.java:115` tool description string

Net deletion. `AgentAssessmentServiceTest` and `HitlWorkflowServiceTest` will need their assertions
narrowed — that is the check that the removal is honest.

### G5 — Scenario B/C in the chat

**Shipped** as `explainFor()` — one `Message.analysis` card rendering `/analyze`'s own
`sourceLabel`, `sourceDetail` and steps. One card, not two scenario renderers: the backend already
decided SOP-vs-web and returns that decision in the operator's words, so re-deriving B-vs-C in the
UI would be a second opinion it has no business holding.

`planFor()` renders an escalation card (`ChatPage.tsx:763-772`, `renderBot` at `:1307`) that shows a
bare reason code and one sentence. The RCA and web-researched steps the requirement asks for already
exist behind `POST /api/v1/incidents/analyze` — the incident page calls it, the chat does not.

When `planFor` escalates, call `/analyze` with the incident's subject and description and render its
`steps` under the escalation card, labelled with the `source` it already returns (`SOP` / `WEB` /
`AI` / `NONE`). That distinguishes B from C with no new backend:

- `source: "SOP"` → SOP exists, no runnable tool → Scenario B, and the card must say so explicitly
- `source: "WEB"` / `"AI"` → no SOP → Scenario C

~30 lines TSX. The endpoint is rate-limited already (`IncidentController.java:113-116`), so a user
mashing the button cannot spend the provider budget.

### G6 — Distributed tracing

**Shipped free, and smaller than planned.** `micrometer-tracing-bridge-brave` alone, no
`opentelemetry-exporter-otlp` and no collector: the bridge is what populates MDC, so `traceId` and
`spanId` reach every log line at $0. An exporter is one dependency and one URL away when there is
somewhere to export to.

`management.tracing.sampling.probability: 1.0` is set with no tracer present, so it samples nothing.
Two `pom.xml` dependencies — `micrometer-tracing-bridge-otel` and `opentelemetry-exporter-otlp` —
plus an OTLP endpoint property. Actuator, Prometheus and Micrometer are already wired.

Trace IDs then land in logs via `%mdc{traceId}` in the same `logback-spring.xml` G3 adds. Do both at
once; they touch one file.

### G7 — ITSM credentials out of the database

**Shipped.** `getSecret()` reads `MCP_*` from the environment; `updateIntegrationSettings()` logs and
drops any credential posted to it; `getAllIntegrationSettings()` returns
`serviceNowSecretSet`/`freshserviceSecretSet`/`jiraSecretSet` booleans and never a value, not even a
masked one — a masked value only tells an attacker the length. `IntegrationAdminPanel`'s three
`type="password"` inputs are gone, replaced by a status row naming the variable to set.

Not in the request, but the request adds "enterprise-grade security" on top of a system that writes
`servicenow_password`, `freshservice_api_key` and `jira_api_token` to `config.system_config` in
cleartext (`IntegrationManagerService.java:71,77,85`). It is S1 in the readiness review and it
contradicts `SECURITY.md:39`. Same pattern as the LLM key: read from `MCP_SERVICENOW_PASSWORD`,
`MCP_FRESHSERVICE_API_KEY`, `MCP_JIRA_API_TOKEN`; changeset deletes the rows.

Ships before anything in this plan reaches a network.

### G8 — Stale Anthropic model ID

**Shipped**: `application.yml:101` now pins `claude-haiku-4-5`, and the Anthropic autoconfiguration
is excluded anyway — Ollama-local is the default and the $0 path. The line only decides the bill if
someone switches provider, which is why it is still the cheapest tier.

---

## 3. Three requirements to cut

**~~Knowledge graph — skip it.~~ Built on Postgres instead.** The objection here was to Neo4j, not
to the graph: a second datastore is a second backup story and a second migration tool, and managed
AuraDB alone exceeds the whole $45 ceiling. "Implement all free" made the distinction matter, and the
last sentence of this section turned out to be the design — so the recursive CTE was written rather
than deferred.

`incident.graph_edges` is a view over rows the platform already owns (`incidents`,
`remediation_plans`, `sop_procedure`), so there is nothing to ingest and nothing that can drift:
OCCURRED_ON, AT_STORE, CLASSIFIED_AS, PLANNED, GROUNDED_IN, PRECEDENT, emitted in both directions so
traversal needs no `UNION` per hop. `IncidentGraphService` walks it with one `WITH RECURSIVE`,
depth clamped to [1,3] and capped at 500 edges with `truncated` reported. SOP edges
join through the plan's own `parameters_json.procedureIds` rather than re-deriving
`action_key → action_name` in SQL — that mapping lives in `AgentAssessmentService` and one copy of it
is enough. Cost: $0, no new dependency, no new backup story. The Relationships tab reads it as a
grouped list; a diagram would have been the graph library this section refused.

**Hiding passwords from the Network tab — not achievable, and the real fix is different.** DevTools
shows the request the browser made; moving the password from the JSON body to a header puts it in the
same panel one row up. Encrypting it in JavaScript makes the ciphertext the password — replayable,
and the key ships to the same client. There is no arrangement where the browser authenticates a user
and the browser's own debugger cannot see it. What genuinely protects the credential is already
mostly in place: TLS on the wire, BCrypt at rest, and never logging it (G3). Add `Cache-Control:
no-store` on the auth responses and that requirement is as satisfied as it can be. If an audit
checklist demands the header form regardless, it is a three-line change in `AuthController.login` and
`api.ts:190` — say so and it ships, but it should be recorded as a checklist item, not a control.

**Real-time ingest polling stays a poll.** `scheduledSync()` already exists. Webhooks from three
ITSM vendors mean three inbound auth schemes, three signature verifications and a public ingress.
The scheduler plus `DistributedLockService` (already built) covers "real-time" at incident cadence.

---

## 4. The $45

The default provider is **Ollama with `phi3:mini` and `nomic-embed-text`, local** — LLM cost is
**$0**, and $45 buys the VM. That is the configuration that meets the ceiling with the most room.

If a hosted provider is wanted, current first-party Anthropic rates:

| Model | Input $/MTok | Output $/MTok |
|---|---|---|
| Claude Haiku 4.5 (`claude-haiku-4-5`) | $1 | $5 |
| Claude Sonnet 5 (`claude-sonnet-5`) | $2 | $10 |
| Claude Opus 5 (`claude-opus-5`) | $5 | $25 |

Haiku 4.5 is the tier that fits. Per-call estimate, using the token profile measured in
[§8](enterprise-readiness.md#8-llm-memory-context-and-cost) **after** its fix #1 (narrow
`AGGREGATE_TERMS`, which cuts 50 ticket rows to ~20):

| Call | Tokens in / out | Cost |
|---|---|---|
| Chat question | ~2,000 / 400 | $0.004 |
| Script generation | ~3,000 / 1,200 | $0.009 |
| `/analyze` suggestion | ~2,500 / 800 | $0.007 |

A month of 2,000 chat questions, 300 plans and 300 analyses ≈ **$14**. Embeddings stay on local
Ollama at $0. Prompt caching cuts the repeated system preamble further on cache hits; it is worth
enabling but the budget closes without it.

**What actually threatens the $45 is not the model tier:**

1. **§8 fix #1 first.** `AGGREGATE_TERMS` contains bare words — `which`, `show`, `status`, `next` —
   so nearly every question ships 50 ticket rows. Unfixed, input is 2-3× the table above and the
   estimate roughly triples.
2. **Neo4j.** Managed graph hosting alone is over budget. See §3.
3. **§8 fix #7 (metering).** Nothing counts tokens today, so every number above is an estimate.
   Spring AI returns usage on the response and `incident.telemetry_events` already exists. Record
   prompt/completion tokens per call and the budget becomes observable instead of argued about.

If "$45" meant a one-off build budget rather than a monthly run rate, say so — it changes the phase
ordering below, not the technical content.

---

## 5. Order of work

**Phase 0 — before this is reachable from a network.** G7 (ITSM secrets), G3 step 1
(`org.springframework.ai: WARN`), plus readiness §9 items 2-6. Nothing below matters until these
land.

**Phase 1 — the requested feature delta.** G1 (resolution prompt + write-back), G2 (collapsible
log), G5 (Scenario B/C in chat). This is what makes the three scenarios complete end-to-end in the
UI, and it is roughly 120 lines of frontend against one 6-line backend change.

**Phase 2 — deletion.** G4 (confidence, schema + UI + scoring). Do it after Phase 1 so the two
changes are not reviewed in one diff; do it before Phase 3 so tracing is not instrumenting code
about to be removed.

**Phase 3 — observability.** G6 (tracing) + G3 step 2 (log masking converter). One
`logback-spring.xml`, two pom dependencies.

**Phase 4 — cost.** §8 fix #7 (meter), then #1 (aggregate terms), then G8 (model ID) if a hosted
provider is used. Metering first, because everything after it is otherwise unmeasurable.

---

## 6. Checks

One runnable check per non-trivial change, no new frameworks:

- **G1** — no test: after the re-scope above it is a fetch and a render against an endpoint that
  already existed. The check that mattered was the one that stopped it being written — HITL's own
  internal `setStatus` calls, which is why the write-back was not moved into `updateIncident()`.
- **G3** — `MaskingConverterTest`: a log line containing `password: hunter2`, `10.1.2.3` and
  `store-0042-pos-01` comes out `****` on all three. Reuses `PublicReadServiceTest`'s cases.
- **G4** — narrow `AgentAssessmentServiceTest` to assert route is `HITL_REQUIRED` iff SOP evidence
  and a non-blank action are both present, and `ESCALATE` otherwise. This is the test that proves the
  gate was removed rather than defaulted to a passing value.
- **G5** — no test; it is a fetch and a render.
- **G6/G7** — `ApplicationContextSmokeTest.theLoginResponseIsNeverCached`: the credential endpoint
  answers with `Cache-Control: no-store`. Spring Security's default header writers already send it,
  so this asserts nothing new — it fails the build if someone calls `http.headers(...)` and turns
  the defaults off while chasing a caching problem elsewhere.
- **§8 #1** — `isAggregateQuestion("show me the printer issue at store 42")` is `false`;
  `isAggregateQuestion("how many incidents are open")` is `true`.
