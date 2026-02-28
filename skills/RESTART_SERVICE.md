# Skill: RESTART_SERVICE

Restarts a named OS service on the local machine (the machine running the MCP application). For restarting services on **remote** servers, use [REMOTE_EXEC](REMOTE_EXEC.md).

---

## Action Key Format

```
RESTART_SERVICE:<service-name>:<os-type>
```

| Parameter | Values | Example |
|-----------|--------|---------|
| `service-name` | Service identifier | `tomcat`, `nginx`, `myapp` |
| `os-type` | `linux` or `windows` | `linux` |

### Examples

```
RESTART_SERVICE:tomcat:linux
RESTART_SERVICE:nginx:linux
RESTART_SERVICE:postgresql:linux
RESTART_SERVICE:MyAppService:windows
RESTART_SERVICE:W3SVC:windows
```

---

## Execution: Linux

Uses **systemctl** for standard services:

```bash
systemctl restart <service-name>
```

Special handling for **Tomcat** (when service name contains `tomcat`):
```bash
# Tries catalina.sh if systemctl is not available
$CATALINA_HOME/bin/catalina.sh stop
sleep 5
$CATALINA_HOME/bin/catalina.sh start
```

The command runs via `ProcessBuilder` with a 60 second timeout.

---

## Execution: Windows

Uses **sc.exe** via PowerShell:

```powershell
Stop-Service -Name "<service-name>" -Force
Start-Service -Name "<service-name>"
```

Falls back to `sc.exe`:
```
sc stop <service-name>
sc start <service-name>
```

---

## Return Values

```json
{
  "success": true,
  "service": "tomcat",
  "os": "linux",
  "output": "Restarted tomcat successfully",
  "durationMs": 8234
}
```

On failure:
```json
{
  "success": false,
  "service": "tomcat",
  "os": "linux",
  "error": "Process exited with code 1: Unit tomcat.service not found",
  "durationMs": 1052
}
```

---

## SOP Example (SQL)

```sql
INSERT INTO sop_procedure (sop_id, step_number, title, description, action_type, execution_order, requires_approval)
VALUES
  (1, 2, 'Restart Application Server',
   'Restart the Tomcat service to clear the stuck threads',
   'RESTART_SERVICE:tomcat:linux', 20, true);
```

> **Important:** `requires_approval = true` is recommended for restart operations — a human-in-the-loop approval step prevents unintended restarts.

---

## When to Use RESTART_SERVICE vs REMOTE_EXEC

| Scenario | Use |
|----------|-----|
| MCP app and target service on same host | `RESTART_SERVICE` |
| Target service on a different server | `REMOTE_EXEC` with APPLICATION category |
| Need pre/post checks as part of restart | `REMOTE_EXEC` (generates full script with health checks) |
| Windows service on remote host | `REMOTE_EXEC:win-host:windows:APPLICATION:Restart <service>` |

---

## Supported Service Patterns

| Service Name Pattern | Linux Command | Windows Command |
|---------------------|---------------|-----------------|
| `tomcat` | `systemctl restart tomcat` | `Stop-Service Tomcat9` |
| `nginx` | `systemctl restart nginx` | N/A |
| `postgresql` | `systemctl restart postgresql` | `Restart-Service postgresql-x64-14` |
| `redis` | `systemctl restart redis` | `Restart-Service Redis` |
| `<any>` | `systemctl restart <name>` | `Stop-Service <name>; Start-Service <name>` |

---

## Limitations

- Runs on the **local** host only (where MCP app is deployed)
- Requires the MCP process to have sufficient OS privileges (run as root/Administrator or with sudo rights)
- 60 second execution timeout — services that take longer to restart will report a timeout error
- No pre/post health checks built-in — add `CHECK_URL` procedures before and after
