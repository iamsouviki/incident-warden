# Skill: CLEAR_CACHE

Flushes a cache tier. Supports Redis, Memcached, and local filesystem cache directories. For remote cache flush operations, use [REMOTE_EXEC](REMOTE_EXEC.md) with the PERFORMANCE category.

---

## Action Key Format

```
CLEAR_CACHE:<cache-type>:<host>:<port>
```

| Parameter | Values | Example |
|-----------|--------|---------|
| `cache-type` | `redis`, `memcached`, `local` | `redis` |
| `host` | Hostname or IP | `localhost`, `cache-server-01` |
| `port` | TCP port | `6379` (Redis), `11211` (Memcached) |

### Examples

```
CLEAR_CACHE:redis:localhost:6379
CLEAR_CACHE:redis:cache-server-01:6379
CLEAR_CACHE:memcached:localhost:11211
CLEAR_CACHE:local:/var/cache/myapp:0
```

---

## Cache-Type Behaviour

### Redis

Executes `FLUSHALL` via the Redis CLI:

```bash
redis-cli -h <host> -p <port> FLUSHALL
```

Or via raw TCP socket (if CLI is unavailable): sends `*1\r\n$8\r\nFLUSHALL\r\n`.

> **Warning:** `FLUSHALL` removes ALL keys in ALL databases. If you need selective flushing, use REMOTE_EXEC with a scoped redis-cli `DEL` or `SCAN`/`DEL` loop. See [REMOTE_EXEC.md PERFORMANCE category](REMOTE_EXEC.md).

### Memcached

Sends the `flush_all` command via raw TCP socket to `<host>:<port>`.

### Local Directory

Deletes all files in the specified path (directory, not `/`):

```bash
rm -rf <path>/*
```

The path is validated — it must:
- Be an absolute path
- Not be `/`, `/etc`, `/var`, `/home`, `/usr`, `/bin`, `/sbin`
- Exist on the filesystem

---

## Return Values

```json
{
  "success": true,
  "cacheType": "redis",
  "host": "localhost",
  "port": 6379,
  "output": "OK",
  "durationMs": 23
}
```

---

## SOP Example (SQL)

```sql
INSERT INTO sop_procedure (sop_id, step_number, title, description, action_type, execution_order, requires_approval)
VALUES
  -- Step 1: flush the cache after a data refresh
  (3, 1, 'Flush Application Cache',
   'Flush the Redis cache to force the application to reload fresh data from the database',
   'CLEAR_CACHE:redis:cache-server-01:6379', 10, true),

  -- Step 2: verify service is still healthy after flush
  (3, 2, 'Verify Cache Server Health',
   'Confirm Redis is still accepting connections after flush',
   'CHECK_URL:http://cache-server-01:8080/health:200', 20, false);
```

---

## When to Use CLEAR_CACHE vs REMOTE_EXEC

| Scenario | Use |
|----------|-----|
| Full Redis FLUSHALL on a local/simple setup | `CLEAR_CACHE:redis:...` |
| Selective key deletion (by pattern) | `REMOTE_EXEC` with PERFORMANCE category |
| Redis cluster flush | `REMOTE_EXEC` (cluster-aware script needed) |
| Application-level cache clear (via API call) | `CHECK_URL` POST equivalent → currently use REMOTE_EXEC with curl |
| Windows app cache directory purge | `REMOTE_EXEC:win-host:windows:PERFORMANCE:...` |

---

## Limitations

- Redis: only `FLUSHALL` — no pattern-based clearing (use REMOTE_EXEC for that)
- No Redis AUTH support — for password-protected Redis, use REMOTE_EXEC
- No Redis TLS support — for TLS Redis, use REMOTE_EXEC
- Memcached: `flush_all` is a soft flush (TTL countdown, not immediate purge on some configs)
- Local directory: only deletes files, not subdirectories; runs on local host only
