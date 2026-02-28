# Skill Reference: GUARDRAILS

The MCP guardrail system is a **5-layer policy engine** that validates every LLM-generated remediation script before it is uploaded or executed via SSH. No script reaches a remote server unless it passes all applicable layers.

This document is the authoritative reference for operators and SREs who need to understand why a script was blocked, how to adjust configuration, or how to write SOP steps that produce guardrail-compliant scripts.

---

## Overview

```
LLM generates script
        │
        ▼
┌─────────────────────────────────┐
│  Layer 1: Structure             │  Shebang, set -e, MCP header, line count
└──────────────────┬──────────────┘
                   │ PASS
                   ▼
┌─────────────────────────────────┐
│  Layer 2: Blocklist             │  Permanently banned command patterns
└──────────────────┬──────────────┘
                   │ PASS
                   ▼
┌─────────────────────────────────┐
│  Layer 3: Command Allowlist     │  Only commands permitted by SOP category
└──────────────────┬──────────────┘
                   │ PASS
                   ▼
┌─────────────────────────────────┐
│  Layer 4: SOP Intent            │  No scope-escape; service name drift check
└──────────────────┬──────────────┘
                   │ PASS / WARN
                   ▼
┌─────────────────────────────────┐
│  Layer 5: Complexity            │  Distinct command count, eval, background jobs
└──────────────────┬──────────────┘
                   │ PASS / WARN
                   ▼
              SSH execution
```

Each layer can produce one of three outcomes:
- **PASS** — layer satisfied, proceed
- **WARN** — potential issue logged; execution continues unless `warn-requires-hitl: true`
- **BLOCK** — `GuardrailBlockException` thrown; script never reaches SSH

---

## Layer 1: Structure

Validates the skeleton of the generated script.

| Check | Linux requirement | Windows requirement | Failure |
|-------|-------------------|---------------------|---------|
| Shebang / error mode | Must start with `#!/bin/bash` or `#!/usr/bin/env bash` | Must contain `$ErrorActionPreference = "Stop"` | BLOCK |
| Error exit | Must contain `set -e` | (covered by ErrorActionPreference) | BLOCK |
| MCP header | Must contain `# [MCP]` | Must contain `# [MCP]` | BLOCK |
| Audit echoes | Must contain at least one `echo` | Must contain at least one `Write-Host` | BLOCK |
| Line count | Lines ≤ `mcp.script-gen.max-lines` (default: 100) | Same | BLOCK |

The MCP header is injected by `ScriptGeneratorService` before guardrail validation — if it's missing, the service itself failed to inject it.

---

## Layer 2: Blocklist

Permanently banned patterns that are **never** allowed regardless of category, SOP, or configuration.

| Pattern | Why banned |
|---------|-----------|
| `rm -rf /` | Wipes entire filesystem |
| `rm -rf /*` | Same as above |
| `format c:` | Windows disk wipe |
| `mkfs.` | Creates new filesystem, destroys existing data |
| `dd if=/dev/zero` | Zeros a block device |
| `dd if=/dev/random` | Same |
| `:(){:\|:&};:` | Fork bomb — crashes system |
| `> /dev/sda` | Overwrites raw disk |
| `> /dev/hda` | Same (older device name) |

These patterns are matched **case-insensitively** anywhere in the script body.

To add permanent bans:
```yaml
mcp:
  script-gen:
    blocklist: "rm -rf /,format c:,mkfs.,dd if=/dev/,..."
```

> The blocklist is checked **before** the allowlist — a banned pattern always wins.

---

## Layer 3: Command Allowlist (Per Category)

Each SOP category has its own allowlist of permitted commands. Any command found in the script that is **not** in the allowlist for the category is blocked.

### APPLICATION
Permitted on Linux:
`systemctl`, `service`, `catalina.sh`, `startup.sh`, `shutdown.sh`, `kill`, `pkill`, `killall`, `curl`, `wget`, `grep`, `ps`, `echo`, `sleep`, `wait`, `if`, `fi`, `then`, `else`, `exit`, `set`

Permitted on Windows:
`Restart-Service`, `Stop-Service`, `Start-Service`, `Get-Service`, `iisreset`, `appcmd`, `Invoke-WebRequest`, `sc.exe`, `sc`, `Write-Host`, `Start-Sleep`, `If`, `Else`, `Exit`

### PERFORMANCE
Permitted on Linux:
`redis-cli`, `psql`, `mysql`, `mysqladmin`, `grep`, `awk`, `sort`, `head`, `tail`, `echo`, `sleep`, `if`, `fi`, `then`, `else`, `exit`, `set`, `wc`, `cut`

Permitted on Windows:
`redis-cli`, `Invoke-WebRequest`, `Invoke-RestMethod`, `schtasks`, `Get-Process`, `Stop-Process`, `Write-Host`, `Start-Sleep`

### DATABASE
Permitted on Linux:
`psql`, `pg_dump`, `pg_restore`, `pg_ctl`, `pg_isready`, `mysql`, `mysqladmin`, `mysqldump`, `grep`, `echo`, `sleep`, `if`, `fi`, `then`, `else`, `exit`, `set`, `awk`

Permitted on Windows:
`sqlcmd`, `Invoke-Sqlcmd`, `Backup-SqlDatabase`, `Restore-SqlDatabase`, `Get-SqlDatabase`, `Write-Host`, `Start-Sleep`

### INFRASTRUCTURE
Permitted on Linux:
`find`, `gzip`, `tar`, `zip`, `unzip`, `df`, `du`, `ls`, `rm` (constrained — see L4), `mv`, `cp`, `mkdir`, `chmod`, `echo`, `grep`, `awk`, `sort`, `head`, `tail`, `wc`, `date`, `if`, `fi`, `then`, `else`, `exit`, `set`

Permitted on Windows:
`Get-ChildItem`, `Remove-Item` (constrained), `Compress-Archive`, `Move-Item`, `Copy-Item`, `New-Item`, `Get-Disk`, `Write-Host`, `Start-Sleep`

### DEPLOYMENT
Permitted on Linux and Windows:
`kubectl`, `helm`, `docker`, `docker-compose`, `echo`, `grep`, `sleep`, `if`, `fi`, `then`, `else`, `exit`, `set`, `Write-Host`, `Start-Sleep`

### SCHEDULED_JOB
Permitted on Linux:
`bash`, `sh`, `python3`, `python`, `java`, `grep`, `echo`, `sleep`, `if`, `fi`, `then`, `else`, `exit`, `set`, `awk`, `date`, `tail`

Permitted on Windows:
`schtasks`, `powershell.exe`, `cmd.exe`, `python`, `java`, `Write-Host`, `Start-Sleep`

---

## Layer 4: SOP Intent

Checks that the script does **only what the SOP step says** — no scope-escape.

### Scope-Escape Patterns (always BLOCK)

| Pattern | Category |
|---------|---------|
| `apt install`, `apt-get install`, `yum install`, `dnf install`, `pip install`, `npm install`, `brew install` | Package installation |
| `^ssh `, `\bssh ` in body (SSH inside the script) | Lateral movement |
| `crontab -e`, `crontab -l` | Cron modification |
| `useradd`, `adduser`, `usermod` | User management |
| `iptables -`, `ufw allow`, `firewall-cmd` | Firewall modification |
| `eval \$`, `eval "$(` | Dynamic code execution |
| `nohup ... &`, `screen -`, `tmux new` | Background process spawning |
| `chmod 777`, `chmod a+w /`, `chown root /` | Permission escalation |
| `sudo su`, `su -`, `sudo -i` | Privilege escalation inside script |

### `rm` Guard

`rm` is only allowed in scripts where the SOP step description mentions one of: `delete`, `clean`, `remove`, `purge`, `clear`, `cleanup`. Otherwise the `rm` command triggers a BLOCK.

**Example SOP step that allows rm:**
> "Clean up log files older than 7 days in /var/log/myapp/"

**Example SOP step that blocks rm:**
> "Restart the application server"

### Service Name Drift Detection

If the SOP category is APPLICATION and the SOP title mentions a specific service name (e.g., "Tomcat"), but the generated script contains `systemctl restart nginx` — the guardrail flags a WARN (or BLOCK if `warn-requires-hitl: true`).

This prevents the LLM from accidentally restarting the wrong service.

---

## Layer 5: Complexity

Validates that the script is not overly complex (which may indicate scope-creep).

| Check | Threshold | Failure |
|-------|-----------|---------|
| Distinct command count | `mcp.script-gen.max-distinct-commands` (default: 15) | WARN |
| `eval` usage | Any `eval` or `Invoke-Expression` | WARN |
| Background job spawning | `&` at end of command line, `Start-Job` | WARN |

WARN-level failures are logged to the MCP application log at `WARN` level. They do NOT block execution unless `warn-requires-hitl: true`.

---

## Configuration Reference

```yaml
mcp:
  script-gen:
    # Maximum number of lines in the generated script (BLOCK if exceeded)
    max-lines: 100

    # Maximum number of distinct commands (WARN if exceeded)
    max-distinct-commands: 15

    # When true, WARN-level findings also prevent execution (forces human review)
    warn-requires-hitl: false

    # Comma-separated list of permanently banned substrings (Layer 2)
    blocklist: "rm -rf /,format c:,mkfs.,dd if=/dev/,dd if=,:(){:|:&};:,> /dev/sda,> /dev/hda"
```

---

## GuardrailBlockException

When any layer blocks a script, `ScriptGuardrailValidator` throws `GuardrailBlockException`:

```
GuardrailBlockException: [L2] Blocklist violation: script contains 'rm -rf /'
GuardrailBlockException: [L3] Command 'apt' not in APPLICATION allowlist
GuardrailBlockException: [L1] Script exceeds max-lines limit: 143 > 100
GuardrailBlockException: [L4] Scope escape: 'apt install' found in script
```

`RemediationToolRegistry.remoteExec()` catches this and returns:
```json
{
  "success": false,
  "error": "Script blocked by guardrail: [L4] Scope escape: 'apt install' found in script",
  "guardrailBlocked": true,
  "host": "app-server-01",
  "durationMs": 0
}
```

No SSH connection is opened. No script is uploaded.

---

## Tuning Guidelines

### Scripts are being blocked too aggressively

1. Check the block reason in the log (`GuardrailBlockException` message).
2. If Layer 3 (command allowlist): the SOP step is using a command outside the category. Either:
   - Change the category in the action key
   - Or add the command to the allowlist in `ScriptGuardrailValidator.CATEGORY_LINUX_ALLOWLIST`
3. If Layer 4 (`rm` guard): add "clean" or "delete" to the SOP step description.
4. If Layer 1 (line count): increase `mcp.script-gen.max-lines`.

### Scripts are not being blocked aggressively enough

1. Set `warn-requires-hitl: true` — all WARNs become BLOCKs.
2. Add patterns to `mcp.script-gen.blocklist`.
3. Reduce `mcp.script-gen.max-distinct-commands`.
4. Review Layer 4 scope-escape patterns in the source code: `ScriptGuardrailValidator.SCOPE_ESCAPE_PATTERNS`.

### How to add a new banned pattern

Option A — Configuration (for simple substring bans):
```yaml
mcp:
  script-gen:
    blocklist: "rm -rf /,...,your-new-pattern"
```

Option B — Code (for regex or complex logic):
Add to `ScriptGuardrailValidator.checkLayer4SopIntent()` in the `SCOPE_ESCAPE_PATTERNS` list.
