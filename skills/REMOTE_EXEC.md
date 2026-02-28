# Skill: REMOTE_EXEC

The most powerful MCP skill. Connects to a remote server via SSH, generates a remediation script **scoped exactly to the SOP step**, validates it through 5 guardrail layers, then uploads and executes it.

---

## Action Key Format

```
REMOTE_EXEC:<hostname>:<os>:<category>:<sop step description>
```

| Parameter | Values | Example |
|-----------|--------|---------|
| `hostname` | Must exist in `server_inventory` | `app-server-01` |
| `os` | `linux` or `windows` | `linux` |
| `category` | `APPLICATION`, `PERFORMANCE`, `DATABASE`, `INFRASTRUCTURE`, `DEPLOYMENT`, `SCHEDULED_JOB` | `APPLICATION` |
| `sop step description` | Free text — becomes the LLM prompt | `Restart the Tomcat application server and verify it is listening on port 8080` |

### Backward-compatible 3-param form (category auto-detected)

```
REMOTE_EXEC:<hostname>:<os>:<sop step description>
```

Category is inferred from keywords in the description:

| Keywords | Inferred Category |
|----------|-------------------|
| tomcat, iis, service, restart | APPLICATION |
| redis, cache, flush | PERFORMANCE |
| postgres, mysql, database, db | DATABASE |
| disk, log, cleanup, archive | INFRASTRUCTURE |
| kubectl, deploy, helm | DEPLOYMENT |
| job, task, batch, cron | SCHEDULED_JOB |

---

## End-to-End Flow

```
1. Parse action key  →  extract hostname, os, category, step description
2. VaultCredentialService.getCredentials(hostname)
       Vault path:  secret/mcp/servers/<hostname>
       Fallback:    env vars MCP_SSH_<HOSTNAME>_USER / _PASSWORD / _KEY
3. ScriptGeneratorService.generateFromSopStep(SopScriptRequest)
       a. LLM call (OpenAI-compatible API)   ← SOP-locked prompt
       b. Inject MCP header into script
       c. Run ScriptGuardrailValidator (5 layers)
       d. If BLOCK → throw GuardrailBlockException  → STOP here
       e. If WARN → log warning + continue (or BLOCK if warn-requires-hitl=true)
4. RemoteExecutionService.execute(credentials, host, script)
       a. JSch createSession → StrictHostKeyChecking=no
       b. SFTP channel → upload script to /tmp/<uuid>.sh  (or C:\Windows\Temp\)
       c. Linux: chmod +x; exec /tmp/<uuid>.sh
          Windows: exec powershell.exe -NonInteractive -File C:\Windows\Temp\<uuid>.ps1
       d. Drain stdout + stderr with 60 s read timeout
       e. Cleanup: sftp.rm(scriptPath) if mcp.remote.cleanup-script=true
5. Return result map: { success, exitCode, output, stderr, durationMs, host, category }
```

---

## Script Structure

Every generated script has this structure:

### Linux (Bash)
```bash
#!/bin/bash
# [MCP] Auto-generated remediation script
# SOP_ID=<sopId>
# SOP_TITLE=<sopTitle>
# SOP_CATEGORY=<category>
# MCP_HOST=<hostname>
# GENERATED_AT=<ISO-8601 timestamp>
# WARNING: This script was auto-generated. Do not modify without SRE review.
set -e

echo "[MCP] Starting: <sopTitle>"
# ... SOP-step-specific commands only ...
echo "[MCP] Completed successfully"
```

### Windows (PowerShell)
```powershell
# [MCP] Auto-generated remediation script
# SOP_ID=<sopId>
# SOP_TITLE=<sopTitle>
# SOP_CATEGORY=<category>
# MCP_HOST=<hostname>
# GENERATED_AT=<ISO-8601 timestamp>
$ErrorActionPreference = "Stop"

Write-Host "[MCP] Starting: <sopTitle>"
# ... SOP-step-specific commands only ...
Write-Host "[MCP] Completed successfully"
```

---

## SopScriptRequest — The Guardrail Contract

`ScriptGeneratorService` receives a `SopScriptRequest` object — NOT a free-form string. This ensures the LLM can only generate what the SOP says.

| Field | Source | Purpose |
|-------|--------|---------|
| `sopStepDescription` | SOP procedure `description` column | Exact text given to LLM as the task |
| `sopCategory` | SOP procedure `category` (or inferred) | Selects command allowlist |
| `sopTitle` | SOP `title` column | Appears in script header and echo statements |
| `sopId` | SOP `id` | Written into script header for traceability |
| `targetHost` | action key param | Written into script header |
| `os` | action key param | Selects Bash vs PowerShell template |
| `allowedCommands` | Auto-populated from category | Handed to LLM as the ONLY commands it may use |
| `additionalContext` | (optional) | Extra constraint text appended to system prompt |

---

## LLM Prompt Design

The LLM receives:

```
SYSTEM: You are a strict remediation script generator. ABSOLUTE RULES:
  1. Implement ONLY the exact steps described in SOP_STEP. Nothing else.
  2. Use ONLY commands from ALLOWED_COMMANDS.
  3. Do NOT: install packages, modify crontabs, open SSH sessions, change firewall rules, modify users.
  4. Do NOT: use eval with external variables, spawn background processes.
  5. Bash scripts: #!/bin/bash + set -e. PowerShell: $ErrorActionPreference="Stop".
  6. Add echo/Write-Host statements for audit trail.
  7. Keep under 80 lines. No markdown code fences.

USER: Generate a <os> script.
  SOP_STEP: <sopStepDescription>
  SOP_CATEGORY: <sopCategory>
  SOP_TITLE: <sopTitle>
  TARGET_HOST: <hostname>
  ALLOWED_COMMANDS: <from category allowlist>
  ADDITIONAL_CONTEXT: <additionalContext if provided>
```

If the LLM API is unavailable or returns an error, `ScriptGeneratorService` falls back to **built-in keyword-matched templates** (e.g., Tomcat restart, Redis flush, psql query kill, nginx restart, kubectl rollout).

---

## Guardrail Layers Summary

See [GUARDRAILS.md](GUARDRAILS.md) for full detail. Quick reference:

| Layer | What It Checks | Failure Action |
|-------|---------------|----------------|
| L1 Structure | Shebang, `set -e`, MCP header, line count ≤ max-lines | BLOCK |
| L2 Blocklist | `rm -rf /`, `format c:`, `mkfs.`, `dd if=/dev/`, fork bombs | BLOCK |
| L3 Command Allowlist | Only commands permitted by category present | BLOCK |
| L4 SOP Intent | No scope-escape (apt install, ssh, crontab, useradd…); service name drift | BLOCK / WARN |
| L5 Complexity | Distinct command count, eval usage, background spawning | WARN (or BLOCK if `warn-requires-hitl=true`) |

---

## Server Inventory

Remote targets must be registered in the `server_inventory` table:

```sql
INSERT INTO server_inventory (hostname, ip_address, os_type, ssh_port, vault_path, environment, tags)
VALUES ('app-server-01', '10.0.1.10', 'LINUX', 22, 'secret/mcp/servers/app-server-01', 'production', '["tomcat","app"]');
```

| Column | Description |
|--------|-------------|
| `hostname` | Must match action key param |
| `ip_address` | Resolved by `RemoteExecutionService` if hostname DNS fails |
| `os_type` | `LINUX` or `WINDOWS` |
| `ssh_port` | Default 22 (Linux) or 22/5985 (Windows) |
| `vault_path` | Vault KV v2 path for this server's SSH credentials |
| `environment` | `production`, `staging`, `dev` |
| `tags` | JSON array — searchable labels |
| `enabled` | `false` = skip server from remote execution |

---

## Configuration

```yaml
mcp:
  remote:
    connection-timeout-ms: 10000   # JSch TCP connect timeout
    command-timeout-ms: 60000      # Max script execution time
    linux-script-dir: /tmp         # Upload directory on Linux hosts
    windows-script-dir: C:\Windows\Temp
    cleanup-script: true           # Delete script after execution

  script-gen:
    api-url: https://api.openai.com/v1/chat/completions
    api-key: ${OPENAI_API_KEY:}    # Empty = use template fallback
    model: gpt-4o
    max-tokens: 768
    temperature: 0.1               # Low = deterministic
    api-timeout-ms: 30000
    max-lines: 100
    max-distinct-commands: 15
    warn-requires-hitl: false
    blocklist: "rm -rf /,format c:,mkfs.,dd if=/dev/,..."
```

---

## Example SOP Procedure (SQL)

```sql
INSERT INTO sop_procedure (sop_id, step_number, title, description, action_type, execution_order, requires_approval)
VALUES (
  1,    -- FK to sop table
  1,
  'Restart Tomcat Application Server',
  'Stop the Tomcat service gracefully. Wait 10 seconds. Start it again. Verify the process is running and listening on port 8080.',
  'REMOTE_EXEC:app-server-01:linux:APPLICATION:Stop the Tomcat service gracefully. Wait 10 seconds. Start it again. Verify the process is running and listening on port 8080.',
  10,
  true
);
```

---

## Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| `GuardrailBlockException: [L2] Blocklist` | LLM hallucinated a dangerous command | Tighten `sopStepDescription`, add more context |
| `GuardrailBlockException: [L3] Command not in allowlist` | Generated command not in category allowlist | Change category or add command to allowlist in `ScriptGuardrailValidator` |
| `GuardrailBlockException: [L4] Scope escape` | LLM tried to install packages or open SSH | Use template fallback; simplify step description |
| `SSH connection refused` | Server not reachable or wrong port | Check `server_inventory.ssh_port` and network ACLs |
| `Auth fail` | Vault returned wrong credentials | Verify Vault path; check `VaultCredentialService` env fallback |
| `Script template fallback used` | LLM API key not configured | Set `OPENAI_API_KEY` env var or set `mcp.script-gen.api-key` |
