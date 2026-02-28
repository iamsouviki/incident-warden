# Skill: CHECK_URL

Performs an HTTP GET health probe against a URL and asserts the expected HTTP status code. Used to verify that a service is up before or after a remediation action.

---

## Action Key Format

```
CHECK_URL:<url>:<expected-status-code>
```

| Parameter | Description | Example |
|-----------|-------------|---------|
| `url` | Full URL including scheme | `http://localhost:8080/actuator/health` |
| `expected-status-code` | HTTP status code to assert | `200` |

### Examples

```
CHECK_URL:http://localhost:8080/actuator/health:200
CHECK_URL:https://api.example.com/status:200
CHECK_URL:http://10.0.1.10:9090/metrics:200
CHECK_URL:http://nginx-host/health:200
```

> **Note:** Colons in the URL (e.g., `http://`) are handled correctly — the parser re-joins all URL parts before extracting the trailing status code.

---

## What It Does

1. Opens an `HttpURLConnection` GET request to the URL.
2. Sets a 10 second connect + read timeout.
3. Reads the response code.
4. Compares with the expected status code.
5. Returns a result map with: `success`, `statusCode`, `expectedCode`, `url`, `durationMs`.

---

## Return Values

```json
{
  "success": true,
  "statusCode": 200,
  "expectedCode": 200,
  "url": "http://localhost:8080/actuator/health",
  "durationMs": 142
}
```

On failure:
```json
{
  "success": false,
  "statusCode": 503,
  "expectedCode": 200,
  "url": "http://localhost:8080/actuator/health",
  "durationMs": 85
}
```

On connection error:
```json
{
  "success": false,
  "error": "Connection refused: localhost:8080",
  "url": "http://localhost:8080/actuator/health",
  "durationMs": 10001
}
```

---

## Common Use Cases in SOPs

### Pre-check (verify the issue exists before remediating)
```sql
action_type = 'CHECK_URL:http://app-server/health:200'
execution_order = 5   -- run first
requires_approval = false
```

### Post-check (verify remediation succeeded)
```sql
action_type = 'CHECK_URL:http://app-server/health:200'
execution_order = 99  -- run last
requires_approval = false
```

### Multi-endpoint check
Create multiple SOP procedures, one per endpoint:
```
CHECK_URL:http://app-server:8080/health:200
CHECK_URL:http://app-server:8080/api/v1/status:200
CHECK_URL:http://app-server:9090/metrics:200
```

---

## SOP Example (SQL)

```sql
INSERT INTO sop_procedure (sop_id, step_number, title, description, action_type, execution_order, requires_approval)
VALUES
  -- Pre-check: confirm service is down before taking action
  (1, 1, 'Verify Service Health', 'Check that the application health endpoint returns 200',
   'CHECK_URL:http://app-server-01:8080/actuator/health:200', 5, false),

  -- Post-check: confirm service recovered after restart
  (1, 4, 'Confirm Recovery', 'Verify the application is healthy after restart',
   'CHECK_URL:http://app-server-01:8080/actuator/health:200', 40, false);
```

---

## Configuration

No dedicated configuration block. The skill uses:
- `HttpURLConnection` with 10 s connect + read timeout
- Follows up to 5 redirects
- No authentication (for authenticated health checks, use REMOTE_EXEC with curl)

---

## Limitations

- GET only — no POST or custom headers
- No TLS certificate validation bypass — for self-signed certs use REMOTE_EXEC with `curl -k`
- No response body inspection — checks status code only
- Single endpoint per action key — use multiple procedures for multi-endpoint checks
