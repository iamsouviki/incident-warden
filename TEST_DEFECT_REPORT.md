# Feature-Branch Test Defect Report

**Branch tested:** `feature/universal-hitl-automation`  
**Revision tested:** `b4db336` (`feat: import Freshservice and ServiceNow exports`)  
**Test mode:** Local H2 profile with the application started temporarily in safe simulation mode.  
**Scope:** Build, unit tests, frontend build, dependency audit, authenticated API paths, CSV import, plan creation, approval, and dry run.

## Executive conclusion

The project **builds**, but the current implementation is **not ready for a real end-to-end safe-remediation test**. The most serious reproduced problem is a **fail-open guardrail**: the application accepted, routed for approval, approved, and dry-ran a remediation plan even though its SOP/RAG service explicitly said that no knowledge service was available.

> A plan must never be considered safe merely because a human can approve it. The system’s intended safety contract requires trusted SOP evidence before it creates an approval request.

The test pass also reproduced incorrect parsing of ordinary quoted CSV data, incorrect ServiceNow numeric-priority handling, misleading Ollama model discovery, insufficient automated coverage, and vulnerable production dependencies.

| Status | Count |
|---|---:|
| **Critical** | 1 |
| **High** | 5 |
| **Medium** | 4 |
| **Informational / environment** | 2 |

## What passed

| Test | Result | Evidence |
|---|---|---|
| Backend compile and tests | **Passed** | Maven test build completed successfully. |
| Frontend production build | **Passed** | Vite build completed successfully. |
| Local startup | **Passed** | Local H2 profile served `/api/health` with HTTP 200. |
| Login | **Passed** | `POST /api/auth/login` with the documented local administrator credentials returned HTTP 200 and a JWT. |
| Authenticated configuration and incident APIs | **Passed** | `GET /api/v1/ai/config`, `GET /api/v1/incidents`, and `GET /api/v1/hitl/requests` returned HTTP 200 with the JWT. |
| Simple Freshservice CSV import | **Passed** | First import created one incident; a repeat import deduplicated it. |
| Approval and simulation state transition | **Passed technically** | A pending plan could be approved and dry-run; however, the plan should never have been eligible because SOP evidence was unavailable. |

## Reproduced defects

### DEF-01 — Critical: SOP-unavailable plan passes guardrails and can be approved

**Severity:** Critical  
**Status:** Reproduced  
**Area:** HITL plan generation and guardrails

A Freshservice printer incident was imported and a plan was created while the local RAG/SOP provider was disabled. The plan response contained this evidence:

> `The SOP knowledge service is not available in this environment. Start the configured knowledge provider or use the local Docker profile.`

Despite that message, the API returned a plan with `guardrailStatus: PASS`, `status: PENDING_APPROVAL`, and `route: HITL_REQUIRED`. The plan was then approved through the approval endpoint and dry-run successfully.

| Observed field | Actual value |
|---|---|
| Action | `clear-printer-queue` |
| SOP evidence | SOP service explicitly unavailable |
| Guardrail status | `PASS` — incorrect |
| Plan status | `PENDING_APPROVAL` — incorrect |
| Approval | Accepted — incorrect |
| Dry run | Recorded as `SIMULATED` |

**Likely cause:** `GuardrailService` uses brittle text matching. It blocks evidence containing `unavailable` or `no tenant-approved sop`, while the unavailable service returns the phrase `not available`. The workflow treats the raw string as trusted evidence rather than using a typed RAG result with an explicit availability/evidence flag.

**Required fix:** Change the RAG contract to return a typed result such as `{ available, tenantScoped, matchedProcedureIds, answer }`. The plan creator must immediately set the plan to `BLOCKED` and incident to `ESCALATED` unless `available=true`, at least one approved procedure ID is present, the procedure belongs to the active tenant, and the evidence passes validation. Do not use phrase matching for this control. Add regression tests for every RAG failure message.

---

### DEF-02 — High: CSV importer corrupts valid quoted fields

**Severity:** High  
**Status:** Reproduced  
**Area:** Freshservice/ServiceNow CSV import

The importer uses `String.split(",")`, which is not a CSV parser. A normal quoted description containing a comma was accepted but stored incorrectly.

**Input row:**

```csv
FS-TEST-1002,Printer message,"Paper jam, tray two needs inspection",High,Hardware
```

**Actual stored result:**

| Field | Expected | Actual |
|---|---|---|
| Description | `Paper jam, tray two needs inspection` | `"Paper jam` |
| Priority | `P2` / high | `P3` |
| Category | `Hardware` | `High` |

The application returns HTTP 200 and reports the row as created, so data corruption is silent.

**Required fix:** Replace manual comma splitting with Apache Commons CSV or an equivalent RFC 4180-compliant parser. Add tests for commas, quotes, escaped quotes, empty cells, multiline descriptions, UTF-8, and column order changes. Do not report a row as successfully imported unless all mapped fields are parsed correctly.

---

### DEF-03 — High: ServiceNow numeric priority is silently downgraded

**Severity:** High  
**Status:** Reproduced  
**Area:** ServiceNow export normalization

A standard ServiceNow-style export with `priority=2` was imported as `P3` instead of `P2`.

**Input row:**

```csv
INC-TEST-2001,Store VPN unavailable,Store 4 VPN session cannot connect,2,Network Team,store-004-vpn
```

**Actual stored result:**

| Field | Expected | Actual |
|---|---|---|
| External source | ServiceNow | ServiceNow |
| Ticket reference | INC-TEST-2001 | INC-TEST-2001 |
| Priority | P2 | **P3** |
| Category | Intended incident category | `Network Team` (assignment group incorrectly used as category) |

**Likely cause:** The current priority mapper recognises only `CRITICAL`, `HIGH`, `P1`, `P2`, and `P3`; numeric provider values are treated as unknown and default to `P3`.

**Required fix:** Add provider-specific mappings. For ServiceNow, map `1` → `P1`, `2` → `P2`, `3` → `P3`, with an explicit policy for priorities 4 and 5. Keep `assignment_group` separate from category; map it to an assigned-team/group field only after validation.

---

### DEF-04 — High: Ollama model discovery hides connectivity failures with a fake model list

**Severity:** High  
**Status:** Reproduced  
**Area:** AI configuration / Ollama dropdown

When the backend could not reach Ollama, `GET /api/v1/ai/config/ollama-models` returned HTTP 200 and a hard-coded model list. It included models that were not installed in the test environment and did not include the user’s actual `qwen3:14b` model.

**Impact:** Operators may select a non-existent model, save the configuration, and only discover the problem during SOP/RAG or incident analysis. The UI gives a false impression that local discovery succeeded.

**Required fix:** Remove the fake fallback list. Return a clear `502 Bad Gateway` or structured `503` response with the sanitized connection error. The frontend should show “Ollama is unreachable” and retain the previous saved model choice, not present unverified options. Add an integration test against a mocked unavailable Ollama endpoint and a mocked tags response.

---

### DEF-05 — High: Full safe workflow cannot be tested from the local profile as documented

**Severity:** High  
**Status:** Reproduced  
**Area:** Local developer experience and RAG flow

The local H2 profile disables Ollama chat/embedding and pgvector. The plan flow therefore receives an unavailable SOP message. Combined with DEF-01, this allows a false “safe” approval path instead of a clear blocked result.

**Impact:** A developer following the local test path can believe SOP-backed HITL has passed when the critical SOP control was not present.

**Required fix:** Provide two explicit local modes:

1. `local-basic`: no RAG, no plan creation permitted; all HITL plans block with `SOP_SERVICE_UNAVAILABLE`.
2. `local-postgres-ollama`: PostgreSQL + pgvector + Redis + Ollama; this is the required profile for a full SOP-to-HITL acceptance test.

The UI must show a prominent capability status panel rather than silently falling back.

---

### DEF-06 — High: Application-layer tenant controls are incomplete for legacy SOP/RAG retrieval

**Severity:** High  
**Status:** Static review; not exercised without PostgreSQL/pgvector  
**Area:** Multi-tenancy and RAG

The new plan, approval, audit, and intake records are tenant-owned. However, the legacy vector/SOP retrieval route was not fully updated to filter every semantic and lexical retrieval query by tenant metadata. A plan can therefore rely on SOP text whose isolation is not guaranteed.

**Required fix:** Add tenant ID to every SOP chunk at ingestion and require it in every vector and lexical retrieval filter. Enforce PostgreSQL Row-Level Security as a second boundary. Add cross-tenant tests proving that a tenant cannot retrieve, plan from, list, update, or delete another tenant’s SOP.

---

## Medium-priority defects and gaps

| ID | Finding | Evidence / impact | Required remediation |
|---|---|---|---|
| DEF-07 | Imported result does not return created incident IDs | Bulk import returns counts only. A caller cannot programmatically progress a just-created record to plan creation without performing a second search. | Return a bounded array of created/deduplicated records with ID, source reference, source, and per-row result. |
| DEF-08 | Only two automated tests execute | Maven output showed only `GuardrailServiceTest`, with two tests. No test exercises login, import, provider mappings, plan creation, approval, dry run, audit chain, or tenant boundaries. | Add unit tests and Spring Boot integration tests for all critical routes, plus PostgreSQL/Testcontainers integration tests for pgvector and migrations. |
| DEF-09 | Production dependency audit remains unsafe | `npm audit --omit=dev` returned 5 production advisories: 4 high and 1 moderate. | Upgrade/replace vulnerable production packages; add audit and lockfile checks to CI; record exceptions with expiry if an upgrade cannot be immediate. |
| DEF-10 | The new HITL plan/approval endpoints are not fully wired to a review UI | The workflow is accessible by `/api/v1/hitl/...` API endpoints, while the incident workspace continues to use older incident-analysis/tool patterns. | Implement a plan card with SOP evidence, confidence components, target, guardrail results, rollback plan, approve/reject action, and immutable plan hash. Remove or clearly label legacy direct script paths. |

## Informational findings

| ID | Finding | Meaning |
|---|---|---|
| INF-01 | Unauthenticated `GET /api/auth/login` returned 403 | The login controller supports POST only. This is not a defect by itself; direct browser GET is unsupported. |
| INF-02 | The local startup emitted a generated Spring Security password message | The custom filter chain still handled the tested routes successfully. Nevertheless, inspect authentication auto-configuration and remove unused default user configuration to avoid operator confusion. |

## Test evidence summary

| Test activity | Result |
|---|---|
| `mvn clean test` | Passed, but only 2 tests executed. |
| `npm ci --ignore-scripts && npm run build` | Passed. |
| `npm audit --omit=dev` | Failed audit gate: 5 production vulnerabilities. |
| Local H2 application startup | Passed; health endpoint returned HTTP 200. |
| JWT login and authorized API calls | Passed. |
| Freshservice simple CSV create + repeat deduplication | Passed. |
| Freshservice quoted CSV | Failed: silent data corruption. |
| ServiceNow numeric priority export | Failed: priority downgraded to P3. |
| Plan with unavailable SOP service | Failed safety condition: plan passed, approval/dry-run possible. |
| Approved dry run | Simulation returned success and performed no real mutation. |

## Recommended remediation order

| Order | Work item | Release gate |
|---:|---|---|
| 1 | Fix DEF-01 so no plan is eligible without typed, tenant-approved SOP evidence. | **Block all HITL testing until complete.** |
| 2 | Replace the CSV parser and add provider-specific ServiceNow/Freshservice mappings. | **Block spreadsheet imports until complete.** |
| 3 | Remove fake Ollama model fallback and add a provider-health status endpoint. | **Block configuration save on unverified models.** |
| 4 | Complete tenant filtering and database RLS for SOP/RAG. | **Block multi-tenant deployment.** |
| 5 | Build the actual HITL review UI and remove/label legacy action paths. | **Block non-technical operator rollout.** |
| 6 | Add integration tests and repair production dependency vulnerabilities. | **Block production release.** |
| 7 | Add a separate, independently allow-listed executor only after the above controls pass. | **Keep execution simulation-only.** |

## Final release recommendation

**Do not enable autonomous or real remediation execution.** The current system should be used only for UI development, API prototyping, and safe simulation after DEF-01 through DEF-03 are fixed. The existing simulated dry run correctly avoided a real mutation during this test, but it was reached through a plan that should have been blocked. That safety failure is a release blocker.
