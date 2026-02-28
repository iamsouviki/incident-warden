# MCP Skills Reference

This directory contains the reference documentation for every **skill** (action tool) available in the MCP Incident Automation engine.

Skills are the atomic units of remediation. When the AI agent selects a remediation action from a SOP procedure, it maps that action to one of these skills and executes it.

---

## Skill Catalogue

| Skill | Action Key Prefix | Description |
|-------|-------------------|-------------|
| [REMOTE_EXEC](REMOTE_EXEC.md) | `REMOTE_EXEC` | SSH into a remote server, generate a SOP-scoped script via LLM, validate through 5-layer guardrails, upload and execute |
| [CHECK_URL](CHECK_URL.md) | `CHECK_URL` | HTTP health probe — GET a URL and assert the expected status code |
| [RESTART_SERVICE](RESTART_SERVICE.md) | `RESTART_SERVICE` | Restart a local OS service (systemctl / Tomcat catalina / Windows sc) |
| [CLEAR_CACHE](CLEAR_CACHE.md) | `CLEAR_CACHE` | Flush a cache tier (Redis FLUSHALL, Memcached flush_all, local directory purge) |
| [RERUN_JOB](RERUN_JOB.md) | `RERUN_JOB` | Re-trigger a batch job or scheduled task (script, schtasks, Jenkins) |
| [SCALE_UP_ROLLBACK](SCALE_UP_ROLLBACK.md) | `SCALE_UP` / `ROLLBACK` | Kubernetes `kubectl scale` or Helm `rollback` |
| [GUARDRAILS](GUARDRAILS.md) | — | Policy engine used by REMOTE_EXEC — 5-layer script validation reference |
| [VAULT_CREDENTIALS](VAULT_CREDENTIALS.md) | — | How to provision server credentials in HashiCorp Vault for REMOTE_EXEC |

---

## How Skills Fit Into the Pipeline

```
Incident Alert
      │
      ▼
 RAG retrieval → SOP procedures matched
      │
      ▼
 ActionExecutorAgent selects next SOP step
      │
      ▼
 RemediationToolRegistry.execute(actionKey)
      │
      ├── CHECK_URL:...       → HTTP probe
      ├── RESTART_SERVICE:... → local process
      ├── CLEAR_CACHE:...     → cache flush
      ├── RERUN_JOB:...       → job trigger
      ├── SCALE_UP:...        → kubectl scale
      ├── ROLLBACK:...        → helm rollback
      └── REMOTE_EXEC:...
              │
              ▼
         VaultCredentialService (Vault KV v2 or env-var)
              │
              ▼
         ScriptGeneratorService.generateFromSopStep(SopScriptRequest)
              │  (LLM prompt locked to SOP step text + category)
              │
              ▼
         ScriptGuardrailValidator (5 layers)
              │  BLOCK → return failure immediately (no SSH)
              │  PASS/WARN → continue
              ▼
         RemoteExecutionService (JSch SSH)
              ├── SFTP upload script to /tmp/<uuid>.sh (or C:\Windows\Temp\)
              ├── chmod +x (Linux)
              ├── exec channel → drain stdout + stderr
              └── cleanup script file (if mcp.remote.cleanup-script=true)
```

---

## Action Key Format

Each skill is invoked by an action key string stored in the SOP procedure's `action_type` column:

```
SKILL_NAME[:param1[:param2[...]]]
```

Examples:

```
CHECK_URL:http://localhost:8080/actuator/health:200
RESTART_SERVICE:tomcat:linux
CLEAR_CACHE:redis:localhost:6379
RERUN_JOB:linux:/opt/batch/nightly_report.sh
REMOTE_EXEC:app-server-01:linux:APPLICATION:Restart the Tomcat application server
SCALE_UP:my-deployment:3
ROLLBACK:my-service:2
```

---

## SOP Procedure Design Guidelines

When writing SOP procedures that use skills:

1. **One step = one action** — each `sop_procedure` row maps to a single skill invocation.
2. **Be explicit in `description`** — for REMOTE_EXEC, the `description` becomes the SOP step text fed directly to the LLM; be specific.
3. **Set `category`** — use one of: `APPLICATION`, `PERFORMANCE`, `DATABASE`, `INFRASTRUCTURE`, `DEPLOYMENT`, `SCHEDULED_JOB`. This controls the command allowlist applied during guardrail validation.
4. **Sequence matters** — `execution_order` determines the order the agent executes steps. Put health checks first, heavy operations last.
5. **Set `requires_approval`** — for destructive actions (restart, rollback), set to `true` to force HITL approval.

---

## Configuration Reference

All skills share this top-level configuration namespace in `application.yml`:

```yaml
mcp:
  vault:           # Credential provider — see VAULT_CREDENTIALS.md
  remote:          # SSH transport settings — see REMOTE_EXEC.md
  script-gen:      # LLM + guardrail settings — see GUARDRAILS.md
```

---

## Adding a New Skill

1. Add a new `case` in `RemediationToolRegistry.execute()`.
2. Parse the action key parameters after the skill prefix.
3. Return a `Map<String, Object>` result with at minimum: `success`, `output`, `durationMs`.
4. Document it in a new `skills/YOUR_SKILL.md` following the format of existing skill docs.
5. Add SOP seed data in a new Flyway migration.
6. Update this README table.
