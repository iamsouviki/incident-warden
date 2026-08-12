# Complete Local Scenario Test Report

**Branch:** `feature/universal-hitl-automation`  
**Revision:** `b4db336`  
**Test execution:** Local H2 profile, temporary Spring Boot runtime, simulation-only configuration  
**Real execution:** **Not enabled; no live infrastructure command was run**

## Executive summary

I ran the application locally and exercised the available API workflow from authentication through import, plan creation, approval, simulated dry run, audit persistence, role boundaries, and legacy script endpoints.

The local application **starts**, health checks work, login works, protected endpoints enforce role boundaries, simple spreadsheet intake works, duplicate detection works, malformed rows are reported, audit records are created in a SHA-256-linked sequence, and simulated dry runs do not run a real operating-system command.

However, the full workflow **fails important safety and data-integrity scenarios**. The main blockers are that plans remain eligible for approval without actual SOP evidence, valid ITSM CSV exports are corrupted when fields contain commas, ServiceNow/Freshservice priorities are mapped incorrectly, the unsafe legacy script endpoint reports success instead of enforcing validation, and model discovery returns fabricated choices when Ollama cannot be reached.

> **Final test verdict: FAIL for safe end-to-end readiness.** The branch is suitable for local prototyping and UI work only. It must remain simulation-only until the critical issues are fixed and retested against PostgreSQL, pgvector, Redis, and a real Ollama instance.

## Test environment and constraints

| Item | Result |
|---|---|
| Backend local profile | Started successfully on port 8080. |
| Local database | H2 file database; not PostgreSQL. |
| Liquibase migrations | Disabled by local H2 profile. |
| Ollama in this test environment | Unavailable. |
| pgvector / real semantic retrieval | Disabled by local H2 profile. |
| Redis | Disabled by local H2 profile. |
| Real command / SSH / cloud / ITSM mutation | Not executed. |

The sandbox in which this test ran does not share the user’s MacBook Ollama or PostgreSQL instance. Therefore, I tested all runnable safe local paths and explicitly marked real Ollama/pgvector scenarios as **blocked**, rather than pretending they passed.

## Scenario results

### Platform, authentication, and authorization

| Scenario | Expected outcome | Actual outcome | Status |
|---|---|---|---|
| Backend health | HTTP 200 | HTTP 200, `status: UP` | **PASS** |
| Invalid username/password | HTTP 401 | HTTP 401 | **PASS** |
| Local admin login | JWT returned | HTTP 200 with JWT | **PASS** |
| Unauthenticated AI config access | HTTP 403 | HTTP 403 | **PASS** |
| Admin AI config access | HTTP 200 | HTTP 200 | **PASS** |
| Viewer accessing admin config | HTTP 403 | HTTP 403 | **PASS** |
| Viewer submitting incident intake | HTTP 403 | HTTP 403 | **PASS** |
| Viewer creating remediation plan | HTTP 403 | HTTP 403 | **PASS** |
| POC SSO endpoint | Out of enterprise scope | It auto-provisioned a viewer from supplied body values | **OUT OF SCOPE / SECURITY RISK** |

### Ollama and configuration

| Scenario | Expected outcome | Actual outcome | Status |
|---|---|---|---|
| Model discovery when provider is unavailable | Clear unavailable/error response | HTTP 200 with a hard-coded fallback list | **FAIL** |
| Script generation while Ollama is unavailable | Fail quickly with a useful error | Request stalled waiting on unavailable provider | **FAIL** |
| Real model selection using user’s local `qwen3:14b` | Must query the user’s actual Ollama tags endpoint | Blocked because this isolated test runtime cannot reach the user’s MacBook Ollama | **BLOCKED** |

The fallback discovery list is not proof that models are installed. It contained items different from the models reported by the user and hid the connection failure.

### Freshservice and ServiceNow intake

| Scenario | Expected outcome | Actual outcome | Status |
|---|---|---|---|
| Simple Freshservice CSV import | Create one normalized incident | Created one incident | **PASS** |
| Repeat Freshservice import | Deduplicate by tenant/source/reference | One duplicate detected; no duplicate created | **PASS** |
| Invalid Freshservice row with no subject | Reject row and identify error | Row rejected with `sourceSystem and subject are required` | **PASS** |
| Freshservice CSV with quoted comma in description | Preserve the full description and correctly map later columns | Description truncated, priority/category shifted silently | **FAIL** |
| Freshservice `High` priority | Map to P2 | Stored as P3 | **FAIL** |
| ServiceNow `priority=2` | Map to P2 | Stored as P3 | **FAIL** |
| ServiceNow assignment group | Retain as group/team metadata | Used as `category` | **FAIL** |
| XLSX native export import | Parse provider export correctly | Not independently exercised in this run; code uses a separate Apache POI path | **NOT YET VERIFIED** |

### SOP, planning, guardrails, approval, and simulation

| Scenario | Expected outcome | Actual outcome | Status |
|---|---|---|---|
| Real SOP upload + pgvector retrieval | Approved tenant SOP is ingested and retrieved | Blocked: H2 local profile disables real AI and pgvector | **BLOCKED** |
| Plan without available SOP service | Plan must be `BLOCKED`, incident must be `ESCALATED`, no request allowed | Plan became `PENDING_APPROVAL` with `guardrailStatus: PASS` | **CRITICAL FAIL** |
| Approval of no-SOP plan | Must be rejected | HTTP 200; request and plan became `APPROVED` | **CRITICAL FAIL** |
| Dry run of no-SOP plan | Must be rejected | HTTP 200; simulated execution was recorded | **CRITICAL FAIL** |
| Audit event sequence | Intake → plan → approval → simulation should be recorded | Hash-linked event sequence was stored | **PASS, but on an unsafe plan** |

The unavailable SOP response used in the plan was:

> `The SOP knowledge service is not available in this environment. Start the configured knowledge provider or use the local Docker profile.`

Yet the plan was proposed as `clear-printer-queue`, approved, and simulated. The guardrail’s phrase matching accepts `not available` even though it tries to reject `unavailable`.

### Legacy script generation, validation, and execution

| Scenario | Expected outcome | Actual outcome | Status |
|---|---|---|---|
| Unsafe script validation | Return `BLOCK` | HTTP 200 with `level: BLOCK` | **PASS** |
| Unsafe non-dry-run script execution after validator block | Reject execution | HTTP 200 and reported `Execution succeeded` | **CRITICAL FAIL** |
| Dry-run script request | Return simulated output only | HTTP 200 with dry-run-only output | **PASS** |
| Real script execution | Must be impossible in this safety test | No real OS command was executed; endpoint currently fakes console output | **MISLEADING / NOT A REAL EXECUTOR** |

The unsafe script body was accepted by `/api/v1/scripts/execute` even though `/api/v1/scripts/validate` had blocked it. The execution response said that the command had run successfully, although the implementation only printed a mock response. This is dangerous because it creates a false record of success and bypasses the intended HITL plan/approval route.

## Verified audit sequence

The local H2 audit table contained a linked sequence for the test tenant:

```text
INTAKE_ACCEPTED
INTAKE_ACCEPTED
INTAKE_ACCEPTED
PLAN_CREATED
APPROVAL_REQUESTED
APPROVED
DRY_RUN_COMPLETED
```

Each later event’s `previous_hash` matched the previous event hash. This proves the current writer records a sequence. It does **not** make the overall workflow safe, because the sequence documented an approval that should have been prevented.

## Defects requiring remediation

| Priority | Defect | Why it blocks safe testing |
|---:|---|---|
| P0 | Fail-open SOP guardrail | The system creates and approves a remediation plan even when SOP evidence is unavailable. |
| P0 | Legacy script executor bypasses validation and HITL | A blocked script receives a simulated `Execution succeeded` result without approval. |
| P1 | CSV parser is not RFC 4180 compliant | Standard ITSM exports with quoted commas are silently corrupted. |
| P1 | Provider-specific priority/group mapping is wrong | Operational severity can be silently downgraded and assigned metadata misplaced. |
| P1 | Ollama model fallback is fabricated | Operators cannot tell that the model service is unreachable. |
| P1 | AI generation has no effective local failure timeout | UI/API can appear to hang when Ollama is down. |
| P1 | Actual SOP/RAG flow remains unverified | Must be exercised using PostgreSQL + pgvector + the user’s running Ollama. |
| P2 | XLSX mapping lacks scenario coverage | Need fixture-driven tests for real Freshservice and ServiceNow exports. |
| P2 | Only a minimal automated test suite exists | Current Maven run executes only two tests; core integration is unprotected. |
| P2 | Production frontend dependencies have audit findings | Prior audit identified 5 production advisories. |

## Required fix order

1. **Disable or remove `/api/v1/scripts/execute` from the user-facing flow immediately.** It must require an approved HITL plan hash, successful guardrail result, trusted target, role verification, and a separate executor. Until then, it should return a hard failure rather than a mock success.
2. **Fix SOP evidence as a typed fail-closed control.** A RAG result needs `available`, `tenantScoped`, and `approvedProcedureIds` values. Any unavailable/empty/untrusted response must block plan creation.
3. **Replace manual CSV splitting with a proper CSV parser.** Add fixture tests for Freshservice and ServiceNow headers, commas, quotes, newlines, UTF-8, priority mappings, and blank values.
4. **Create provider-specific normalizers.** Keep ServiceNow assignment group separate from category and define an explicit numeric-priority mapping.
5. **Remove the hard-coded Ollama model fallback.** Return a clear provider-health error and show it in the UI. Apply strict connection/read timeouts to generation and RAG requests.
6. **Add real integration tests.** Use Testcontainers PostgreSQL with pgvector and Redis, plus a controlled/mock Ollama tags/chat endpoint. Include positive and negative full-flow tests.
7. **Retest on the user’s local PostgreSQL + Ollama environment.** The full acceptance flow must include SOP ingestion, RAG retrieval, imported incident, blocked unsafe plan, approved safe plan, simulated dry run, and audit verification.

## Release decision

| Capability | Current decision |
|---|---|
| UI exploration | Allowed |
| Import prototyping | Allowed only with test data; mappings need correction |
| SOP-backed planning | Not ready |
| HITL approval | Not safe until P0 SOP failure is fixed |
| Legacy script execution endpoint | Do not expose or use |
| Dry-run simulation | Only after P0 controls are fixed |
| Real remediation / autonomy | **Do not enable** |

**Final verdict:** The application ran locally and several foundational paths worked, but safe end-to-end incident remediation did not pass. Fix the two P0 items first, then rerun the complete suite against PostgreSQL, pgvector, and the user’s active Ollama instance.
