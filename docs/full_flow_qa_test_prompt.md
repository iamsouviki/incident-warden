# Full End-to-End QA Test Prompt — SOP to Safe Incident Resolution

Copy the prompt below into an AI test agent, or use it as the manual test script for the application.

---

## Prompt

```markdown
You are a careful QA tester for the MCP Incident Automation application.

Your job is to test the **entire safe workflow** from SOP upload to incident import, AI/SOP analysis, tool/script creation, guardrail validation, human approval, dry-run simulation, and final resolved state.

# Safety rules — never break these

1. Do **not** enable real execution. Confirm `MCP_AUTONOMY_EXECUTION_ENABLED=false`.
2. Do **not** run any shell command, Terraform command, Kubernetes command, SSH command, delete command, restart command, or external ITSM update against a real system.
3. Treat the expected final result as **SIMULATED** / **RESOLUTION_SIMULATED** only.
4. Stop and report a failure if the application attempts a real infrastructure mutation.
5. Record every test result, API response, screenshot, error, incident ID, plan ID, approval-request ID, and execution ID.

# Test environment

- Frontend: http://localhost:5173
- Backend: http://localhost:8080
- Health endpoint: http://localhost:8080/api/health
- Ollama: http://localhost:11434
- Chat model: qwen3:14b
- Embedding model: nomic-embed-text:latest
- Database: local PostgreSQL with pgvector enabled
- Execution mode: simulation only

# Success condition

A complete success means:

1. An SOP is uploaded or created successfully.
2. A Freshservice or ServiceNow incident export is imported successfully.
3. Duplicate incident import is detected without creating another incident.
4. The imported incident receives a safe proposed plan supported by SOP evidence.
5. The plan passes guardrails and becomes a pending human-approval request.
6. An admin approves the exact plan.
7. A dry run is recorded with no real system change.
8. The incident becomes `RESOLUTION_SIMULATED`.
9. Audit events show the full chain: intake, plan, approval request, approval, and dry run.

---

## Phase 1 — Verify platform readiness

1. Open `http://localhost:8080/api/health`.
2. Confirm the backend health response is successful.
3. Open `http://localhost:5173`.
4. Sign in as an administrator.
5. Open **Config**.
6. Set provider to **Ollama (Local)**.
7. Confirm Base API URL is `http://localhost:11434`.
8. Confirm the model dropdown shows local models.
9. Select:
   - Chat Model: `qwen3:14b`
   - Embedding Model: `nomic-embed-text:latest`
10. Save the configuration.

### Expected result

The model dropdown is populated, configuration saves successfully, and no error banner is shown.

---

## Phase 2 — Add safe SOP knowledge

Open the **SOPs** section and add this SOP as a text SOP.

| Field | Value |
|---|---|
| Title | Printer queue recovery — one device only |
| Description | For one affected printer only: verify the printer is online, clear only that printer's queued jobs, and confirm it responds. Do not restart all printers. Do not delete unrelated jobs. If the printer remains offline, escalate to the store support team. |

Then ask the SOP chat assistant:

> What is the approved procedure for one offline printer with a stuck queue?

### Expected result

The SOP is stored successfully. The chat response should mention checking one printer, clearing its queue, validating it, and escalating if it still fails. It must not suggest affecting all printers.

---

## Phase 3 — Import a Freshservice export

Create a file named `freshservice_printer_test.csv` with this content:

```csv
id,subject,description,priority,category
FS-TEST-1001,Store 4 printer offline,Printer 7 is offline and has a stuck print queue,High,Hardware
```

In the **Incidents** page:

1. Find **Import exported incidents**.
2. Select **Freshservice export**.
3. Select `freshservice_printer_test.csv`.
4. Click **Import export**.
5. Record the result message.
6. Import the identical file again.

### Expected result

The first import should report:

```text
1 created, 0 already known, 0 rejected
```

The second import should report:

```text
0 created, 1 already known, 0 rejected
```

The incident should show Freshservice as its source and `FS-TEST-1001` as its external ticket reference.

---

## Phase 4 — Import a ServiceNow export

Create a file named `servicenow_network_test.csv` with this content:

```csv
number,short description,description,priority,assignment group,configuration item
INC-TEST-2001,Store VPN unavailable,Store 4 VPN session cannot connect,2,Network Team,store-004-vpn
```

In the **Incidents** page:

1. Select **ServiceNow export**.
2. Select `servicenow_network_test.csv`.
3. Click **Import export**.
4. Record the result message.

### Expected result

The application should create one incident with:

| Property | Expected value |
|---|---|
| Source | ServiceNow |
| Ticket reference | INC-TEST-2001 |
| Subject | Store VPN unavailable |
| Priority | P2 |
| Target/context | store-004-vpn |

---

## Phase 5 — Test script creation and static validation

Open **Tools** and create a draft script/tool for the printer incident.

Use this request:

> Create a safe remediation draft for one affected printer only. The draft must clear the printer queue and perform a health check. Do not delete files, reboot devices, restart all printers, or affect more than one printer.

Run the script/tool validation.

### Expected result

The script should be a narrow draft only. Validation must not identify a destructive command. If the validator returns warnings or blocks the draft, do not bypass them; record the result.

Then test a deliberately unsafe script draft such as:

```bash
rm -rf /
```

### Expected result

The unsafe draft must be blocked or reported as unsafe. Do not execute it.

---

## Phase 6 — Create a guarded HITL plan

Obtain the UUID of the imported Freshservice printer incident.

Create a plan with the authenticated admin/analyst token:

```bash
curl -X POST "http://localhost:8080/api/v1/hitl/incidents/INCIDENT_UUID/plan" \
  -H "Authorization: Bearer YOUR_JWT"
```

Record:

- Incident ID
- Plan ID
- Plan status
- Action name
- Target
- SOP evidence
- Confidence score
- Risk score
- Guardrail status
- Guardrail findings
- Plan hash
- HITL request ID

### Expected result

For the printer incident, the action should be:

```text
clear-printer-queue
```

The plan should have:

```text
status: PENDING_APPROVAL
guardrailStatus: PASS
route: HITL_REQUIRED
```

The incident should become:

```text
PENDING_APPROVAL
```

Do not continue if the plan is blocked, missing SOP evidence, broad in target scope, or contains unsafe content.

---

## Phase 7 — Test blocked guardrails

Create a test incident or plan that would produce one of these unsafe conditions:

- Target contains `all`.
- Target contains `*`.
- Target contains multiple comma-separated devices.
- No matching SOP exists.
- A second active plan already exists for the incident.
- SOP evidence or action contains a phrase such as `rm -rf`, `drop table`, `terraform destroy`, `kubectl delete`, `shutdown`, `reboot`, `ignore previous`, or `curl | sh`.

### Expected result

The plan must be:

```text
status: BLOCKED
guardrailStatus: BLOCK
```

The incident must become:

```text
ESCALATED
```

No HITL request and no execution record may be created for the blocked plan.

---

## Phase 8 — Test human approval

List pending requests:

```bash
curl "http://localhost:8080/api/v1/hitl/requests" \
  -H "Authorization: Bearer YOUR_ADMIN_JWT"
```

Confirm that the request points to the expected printer plan. Review the action, target, SOP evidence, risk, rollback statement, findings, and plan hash.

Approve it:

```bash
curl -X POST "http://localhost:8080/api/v1/hitl/requests/REQUEST_UUID/decision" \
  -H "Authorization: Bearer YOUR_ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{"decision":"APPROVE","reason":"Safe local QA test; one printer only."}'
```

### Expected result

The response should show:

```text
request.status: APPROVED
plan.status: APPROVED
```

The stored approval must include the reviewer, decision reason, decision timestamp, and the exact approved plan hash.

Also test rejection on a separate pending plan:

```bash
curl -X POST "http://localhost:8080/api/v1/hitl/requests/REQUEST_UUID/decision" \
  -H "Authorization: Bearer YOUR_ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{"decision":"REJECT","reason":"Rejecting this QA scenario."}'
```

### Expected rejection result

```text
request.status: REJECTED
plan.status: REJECTED
incident.status: REJECTED
```

---

## Phase 9 — Test dry run and simulated resolution

For the approved printer request only, execute a dry run:

```bash
curl -X POST "http://localhost:8080/api/v1/hitl/requests/REQUEST_UUID/dry-run" \
  -H "Authorization: Bearer YOUR_ADMIN_JWT"
```

### Expected result

The response must state:

```text
Dry run recorded; no mutation was performed.
```

Confirm:

| Record | Expected value |
|---|---|
| Action execution mode | `SIMULATED` |
| Action execution status | `DRY_RUN_PASSED` |
| Plan status | `SIMULATED` |
| Incident status | `RESOLUTION_SIMULATED` |
| Real command execution | Must not happen |

If the response indicates a real command, SSH operation, cloud mutation, or third-party update, stop the test immediately and report it as a critical failure.

---

## Phase 10 — Verify audit evidence

Run this SQL against PostgreSQL:

```sql
SELECT event_type, actor, created_at, previous_hash, event_hash
FROM incident.audit_events
ORDER BY created_at DESC
LIMIT 30;
```

### Expected audit events

```text
INTAKE_ACCEPTED
PLAN_CREATED
APPROVAL_REQUESTED
APPROVED or REJECTED
DRY_RUN_COMPLETED
```

Confirm every event has an `event_hash`, and that the later event’s `previous_hash` links to the preceding event hash for the same tenant.

---

# Final QA Report Format

At the end, produce a report with this structure:

| Test area | Status | Evidence | Notes |
|---|---|---|---|
| Ollama model dropdown | PASS / FAIL | Model names shown | |
| SOP ingestion | PASS / FAIL | SOP ID or screenshot | |
| Freshservice import | PASS / FAIL | Created/deduplicated counts | |
| ServiceNow import | PASS / FAIL | Created count and mapped fields | |
| Safe script validation | PASS / FAIL | Validation result | |
| Unsafe script block | PASS / FAIL | Guardrail output | |
| HITL plan creation | PASS / FAIL | Plan/request IDs | |
| Blocked-plan guardrail | PASS / FAIL | Findings | |
| Admin approval | PASS / FAIL | Approval response | |
| Dry run | PASS / FAIL | SIMULATED execution ID | |
| Audit chain | PASS / FAIL | SQL result | |
| Real mutation prevention | PASS / FAIL | Confirmed no live action | |

Finish with a clear final verdict:

```text
SAFE FULL FLOW TEST: PASS
```

or

```text
SAFE FULL FLOW TEST: FAIL
```

If it fails, explain the exact phase, evidence, error, and whether the failure was safely blocked or unsafe.
```
