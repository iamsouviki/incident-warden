# Local Testing Guide — Freshservice/ServiceNow Import and Safe HITL Flow

This guide lets you test the application locally without connecting it to a real production ITSM account or allowing any real remediation action.

> Keep `MCP_AUTONOMY_EXECUTION_ENABLED=false`. The expected final outcome is a **simulated** dry run. No server, printer, network, Kubernetes cluster, Terraform state, or third-party ticket is changed.

## 1. Start the local services

Start PostgreSQL, Redis, Ollama, the backend, and the frontend. With the user’s local models, the recommended environment is:

```bash
export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/mcp_db'
export SPRING_DATASOURCE_USERNAME='mcp_user'
export DB_PASSWORD='your_postgres_password'
export REDIS_HOST='localhost'
export REDIS_PORT='6379'
export OLLAMA_BASE_URL='http://localhost:11434'
export OLLAMA_CHAT_MODEL='qwen3:14b'
export MCP_AUTONOMY_EXECUTION_ENABLED=false

mvn spring-boot:run
```

In a second terminal:

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`, sign in as an administrator, and confirm that `http://localhost:8080/api/health` responds successfully.

## 2. Confirm model discovery

Open **Config** in the app. Select **Ollama (Local)** and use `http://localhost:11434` as the Base API URL. The chat-model dropdown should show installed models such as `qwen3:14b`; the embedding-model dropdown should show `nomic-embed-text:latest`.

Select these values and save:

| Field | Recommended local value |
|---|---|
| Chat Model | `qwen3:14b` |
| Embedding Model | `nomic-embed-text:latest` |

## 3. Add a small SOP first

Before testing a remediation plan, add a simple SOP so the app has approved reference material.

Open **SOPs** and create a text SOP with this content:

| Field | Test value |
|---|---|
| Title | Printer queue recovery |
| Description | For one affected printer: verify the printer is online, clear only its queued jobs, then check that it responds. Do not restart all printers and do not delete unrelated jobs. |

This gives the RAG/SOP system a safe document to retrieve during plan creation.

## 4. Test a Freshservice spreadsheet export

Open **Incidents**. You now see an **Import exported incidents** panel.

1. Choose **Freshservice export**.
2. Select a `.csv` or `.xlsx` export file.
3. Click **Import export**.

The importer recognises common Freshservice-style export columns. A minimal test CSV can look like this:

```csv
id,subject,description,priority,category
FS-10001,Store 4 printer offline,Printer 7 is offline and has a stuck print queue,High,Hardware
```

A successful result shows a message similar to:

```text
Import finished: 1 created, 0 already known, 0 rejected.
```

Import the same file again. The expected result is:

```text
Import finished: 0 created, 1 already known, 0 rejected.
```

That demonstrates the deduplication check using your tenant, the selected source, and the exported ticket ID.

## 5. Test a ServiceNow spreadsheet export

Choose **ServiceNow export** in the same import panel. The importer recognises typical ServiceNow headers such as `number`, `short description`, `description`, `priority`, `assignment group`, `configuration item`, `sys_id`, `impact`, and `category`.

Use this minimal test CSV:

```csv
number,short description,description,priority,assignment group,configuration item
INC0012345,VPN unavailable for one store,Store 4 VPN session cannot connect,2,Network Team,store-004-vpn
```

The importer normalises the export to an internal incident. Ticket reference `INC0012345` becomes the source reference, and `ServiceNow` becomes the incident source even though the spreadsheet does not include a source column.

## 6. Test rejected spreadsheet rows

Use a small CSV with one invalid row:

```csv
number,short description,description
INC0012346,Valid incident,A valid description
INC0012347,,Missing subject must be rejected
```

The result should say that one row was created and one was rejected. The response includes the spreadsheet row number and error reason. This confirms the app does not silently accept broken export data.

## 7. Test plan creation and guardrails

Select the imported printer incident in the incident list. The safe HITL endpoint is currently available through the API; the legacy incident page is not yet wired to the new plan card UI.

Use the incident ID from the page/API and create a plan:

```bash
curl -X POST "http://localhost:8080/api/v1/hitl/incidents/INCIDENT_UUID/plan" \
  -H "Authorization: Bearer YOUR_ADMIN_JWT"
```

Expected safe path:

1. The app retrieves SOP evidence.
2. It proposes `clear-printer-queue` for a printer issue, or `refresh-network-session` for a VPN/network issue.
3. It checks that the action is allow-listed and targets only one incident/ticket.
4. It creates a plan and a `PENDING` HITL request.
5. The incident becomes `PENDING_APPROVAL`.

Expected blocked path:

- A vague incident with no matching SOP.
- A target containing `all`, `*`, or a comma-separated group.
- An unsupported action.
- A second active plan for the same incident.
- Evidence containing destructive or prompt-injection phrases.

For a blocked case, the plan is saved as `BLOCKED`, the incident becomes `ESCALATED`, and no approval request is created.

## 8. Test human approval

List the tenant’s pending requests:

```bash
curl "http://localhost:8080/api/v1/hitl/requests" \
  -H "Authorization: Bearer YOUR_ADMIN_JWT"
```

Approve a request only after inspecting the action, target, SOP evidence, risk score, and guardrail findings:

```bash
curl -X POST "http://localhost:8080/api/v1/hitl/requests/REQUEST_UUID/decision" \
  -H "Authorization: Bearer YOUR_ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{"decision":"APPROVE","reason":"Local safe-flow test"}'
```

The app stores the approver, the time, the reason, and the exact plan hash that was approved.

## 9. Test the dry run

Run only the approved request:

```bash
curl -X POST "http://localhost:8080/api/v1/hitl/requests/REQUEST_UUID/dry-run" \
  -H "Authorization: Bearer YOUR_ADMIN_JWT"
```

Expected result:

```text
Dry run recorded; no mutation was performed.
```

The request must be approved, the plan hash must still match, and the guardrail result must still be `PASS`. The app creates an `action_executions` record in `SIMULATED` mode, sets the plan to `SIMULATED`, and sets the incident to `RESOLUTION_SIMULATED`.

## 10. Verify audit evidence in PostgreSQL

Use PostgreSQL to inspect the recorded workflow history:

```sql
SELECT event_type, actor, created_at, previous_hash, event_hash
FROM incident.audit_events
ORDER BY created_at DESC
LIMIT 20;
```

You should see events such as `INTAKE_ACCEPTED`, `PLAN_CREATED`, `APPROVAL_REQUESTED`, `APPROVED`, and `DRY_RUN_COMPLETED`. Each event has a SHA-256 hash connected to the prior event for the same tenant.

## 11. Success checklist

| Test | Expected result |
|---|---|
| Model dropdown | Shows local Ollama models after authenticated Config-page request. |
| SOP creation | SOP is stored and can be queried. |
| Freshservice CSV/XLSX | Creates normalized Freshservice incidents. |
| ServiceNow CSV/XLSX | Maps `number` and `short description` correctly. |
| Duplicate upload | Does not create a second incident. |
| Invalid row | Is rejected with a row-specific error. |
| Safe plan | Creates a pending HITL request. |
| Unsafe/broad plan | Is blocked and escalated. |
| Admin approval | Binds approval to the exact plan hash. |
| Dry run | Records simulation only; performs no real mutation. |
| Audit query | Shows hash-chained workflow events. |
