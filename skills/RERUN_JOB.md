# Skill: RERUN_JOB

Re-triggers a batch job, scheduled task, or script. Supports Linux shell scripts, Windows scheduled tasks (`schtasks`), and Jenkins pipeline jobs (via HTTP API).

---

## Action Key Format

```
RERUN_JOB:<type>:<identifier>
```

| Parameter | Values | Description |
|-----------|--------|-------------|
| `type` | `linux`, `windows`, `jenkins` | Platform or job system |
| `identifier` | Script path, task name, or Jenkins job URL | What to execute |

### Examples

```
RERUN_JOB:linux:/opt/batch/nightly_report.sh
RERUN_JOB:linux:/opt/etl/data_sync.sh
RERUN_JOB:windows:NightlyReportTask
RERUN_JOB:windows:ETLDataSync
RERUN_JOB:jenkins:http://jenkins.internal/job/DataPipeline/build
RERUN_JOB:jenkins:http://jenkins.internal/job/Reports/job/Nightly/build
```

---

## Execution: Linux

Runs the script directly via `ProcessBuilder`:

```bash
bash <script-path>
```

Requirements:
- Script must be an absolute path
- Script must be executable (or `bash ` prefix handles it)
- Script path must not contain `..` (directory traversal blocked)
- MCP process must have read + execute permissions

---

## Execution: Windows

Re-runs a named scheduled task using `schtasks.exe`:

```
schtasks /Run /TN "<task-name>"
```

The task must already be defined in Windows Task Scheduler. The action triggers an immediate run outside of its normal schedule.

---

## Execution: Jenkins

Triggers a Jenkins build via the remote API:

```
POST http://<jenkins-url>/build
Authorization: Bearer <JENKINS_TOKEN>
```

The Jenkins API token must be configured:

```yaml
mcp:
  jenkins:
    api-token: ${JENKINS_API_TOKEN:}
    crumb-url: ${JENKINS_URL:}/crumbIssuer/api/json   # optional — for CSRF protection
```

Or via environment variable: `JENKINS_API_TOKEN`.

---

## Return Values

```json
{
  "success": true,
  "jobType": "linux",
  "identifier": "/opt/batch/nightly_report.sh",
  "output": "Job started successfully",
  "durationMs": 3421
}
```

Jenkins (async trigger):
```json
{
  "success": true,
  "jobType": "jenkins",
  "identifier": "http://jenkins.internal/job/DataPipeline/build",
  "output": "Build triggered — queue item: http://jenkins.internal/queue/item/42/",
  "durationMs": 312
}
```

---

## SOP Example (SQL)

```sql
INSERT INTO sop_procedure (sop_id, step_number, title, description, action_type, execution_order, requires_approval)
VALUES
  (5, 1, 'Re-run Failed ETL Job',
   'Trigger the ETL data synchronisation script to re-process the failed batch',
   'RERUN_JOB:linux:/opt/etl/data_sync.sh', 10, true),

  (5, 2, 'Verify Data Pipeline Health',
   'Confirm the data pipeline API is reporting healthy status',
   'CHECK_URL:http://etl-service:8080/health:200', 20, false);
```

---

## Security Constraints

| Constraint | Detail |
|-----------|--------|
| No path traversal | Script paths containing `..` are rejected |
| Absolute paths only | Relative paths are rejected for Linux scripts |
| No shell injection | Arguments are passed as array to ProcessBuilder — no string concatenation into shell |
| Jenkins POST only | Only `POST /build` — no script console or groovy API calls |
| Identifier allowlisting | Identifiers are validated against `[a-zA-Z0-9/_\-.]` pattern |

---

## When to Use RERUN_JOB vs REMOTE_EXEC

| Scenario | Use |
|----------|-----|
| Script on **local** MCP host | `RERUN_JOB:linux:...` or `RERUN_JOB:windows:...` |
| Script on **remote** host | `REMOTE_EXEC` with SCHEDULED_JOB category |
| Jenkins / CI trigger | `RERUN_JOB:jenkins:...` |
| Complex job with checkpointing logic | `REMOTE_EXEC` (LLM generates idempotent retry script) |
| Job needs env vars or config injected | `REMOTE_EXEC` with SCHEDULED_JOB (LLM can inject env sourcing) |

---

## Limitations

- Linux/Windows: runs the job **synchronously** — the skill waits for completion (up to 60 s timeout)
- Jenkins: fires-and-forgets (HTTP 201 = triggered) — does not wait for build result
- No job state polling — add a separate `CHECK_URL` procedure to verify job outcome
- No output capture for Jenkins — view results in Jenkins UI
