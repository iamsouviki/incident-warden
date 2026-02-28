-- ──────────────────────────────────────────────────────────────────────────────
-- V8 — Server inventory table + example REMOTE_EXEC SOP action plans
--
-- server_inventory stores the catalogue of target servers that the MCP
-- automation platform is allowed to connect to and remediate.
--
-- REMOTE_EXEC action string format (stored in sop_procedures.action_plan_json):
--   "REMOTE_EXEC:hostname:os:incident description"
--
-- The hostname is looked up in this table at runtime to determine the OS;
-- the same hostname key is used to fetch credentials from HashiCorp Vault at:
--   secret/data/mcp/servers/<hostname>
-- ──────────────────────────────────────────────────────────────────────────────

-- ── Server inventory ─────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS server_inventory (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    hostname     VARCHAR(255) NOT NULL,
    ip_address   VARCHAR(45),
    os           VARCHAR(20)  NOT NULL DEFAULT 'linux'
                                CHECK (os IN ('linux','windows')),
    ssh_port     INT          NOT NULL DEFAULT 22,
    -- Vault KV v2 path where credentials are stored.
    -- NULL = use fallback env-var credentials.
    vault_path   VARCHAR(500),
    environment  VARCHAR(50)  NOT NULL DEFAULT 'production'
                                CHECK (environment IN ('production','staging','dev','dr')),
    description  TEXT,
    -- JSON metadata (e.g. {"role":"app","dc":"us-east"})
    tags         JSONB        NOT NULL DEFAULT '{}',
    -- Last successful SSH connection
    last_reached TIMESTAMP,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE (tenant_id, hostname)
);

CREATE INDEX IF NOT EXISTS idx_server_inventory_tenant  ON server_inventory (tenant_id);
CREATE INDEX IF NOT EXISTS idx_server_inventory_os      ON server_inventory (os);
CREATE INDEX IF NOT EXISTS idx_server_inventory_env     ON server_inventory (environment);

-- ── Seed example servers (tenant = democorp) ─────────────────────────────────

INSERT INTO server_inventory
    (id, tenant_id, hostname, ip_address, os, ssh_port, vault_path, environment, description, tags)
VALUES

-- Linux application server running Tomcat
('a0000000-0000-0000-0000-000000000001',
 '00000000-0000-0000-0000-000000000001',
 'app-server-01', '10.0.1.10', 'linux', 22,
 'mcp/servers/app-server-01', 'production',
 'Primary Tomcat application server',
 '{"role":"app","app":"tomcat","dc":"us-east-1a"}'),

-- Second Linux app server (for scale-out scenarios)
('a0000000-0000-0000-0000-000000000002',
 '00000000-0000-0000-0000-000000000001',
 'app-server-02', '10.0.1.11', 'linux', 22,
 'mcp/servers/app-server-02', 'production',
 'Secondary Tomcat application server',
 '{"role":"app","app":"tomcat","dc":"us-east-1b"}'),

-- Linux database server
('a0000000-0000-0000-0000-000000000003',
 '00000000-0000-0000-0000-000000000001',
 'db-server-01', '10.0.2.10', 'linux', 22,
 'mcp/servers/db-server-01', 'production',
 'Primary PostgreSQL database server',
 '{"role":"db","app":"postgresql","dc":"us-east-1a"}'),

-- Linux Redis cache server
('a0000000-0000-0000-0000-000000000004',
 '00000000-0000-0000-0000-000000000001',
 'cache-server-01', '10.0.3.10', 'linux', 22,
 'mcp/servers/cache-server-01', 'production',
 'Redis primary cache node',
 '{"role":"cache","app":"redis","dc":"us-east-1a"}'),

-- Windows application server (IIS / .NET)
('a0000000-0000-0000-0000-000000000005',
 '00000000-0000-0000-0000-000000000001',
 'win-app-01', '10.0.4.10', 'windows', 22,
 'mcp/servers/win-app-01', 'production',
 'Windows Server 2022 — IIS + .NET application (OpenSSH installed)',
 '{"role":"app","app":"iis","dc":"us-east-1a","os_version":"Windows Server 2022"}'),

-- Windows batch / scheduler server
('a0000000-0000-0000-0000-000000000006',
 '00000000-0000-0000-0000-000000000001',
 'batch-server-01', '10.0.5.10', 'windows', 22,
 'mcp/servers/batch-server-01', 'production',
 'Windows scheduled task / batch processing server',
 '{"role":"batch","dc":"us-east-1a","os_version":"Windows Server 2019"}'),

-- Linux staging app server
('a0000000-0000-0000-0000-000000000007',
 '00000000-0000-0000-0000-000000000001',
 'stg-app-01', '10.0.1.50', 'linux', 22,
 'mcp/servers/stg-app-01', 'staging',
 'Staging Tomcat application server',
 '{"role":"app","app":"tomcat","dc":"us-east-1c","env":"staging"}')

ON CONFLICT (tenant_id, hostname) DO NOTHING;


-- ══════════════════════════════════════════════════════════════════════════════
-- NEW SOP procedures that use REMOTE_EXEC
-- Each demonstrates the LLM-generate-script → SSH pattern
-- ══════════════════════════════════════════════════════════════════════════════

INSERT INTO sop_procedures (
    id, tenant_id, title, description, category,
    action_plan_json, version, status, reliability_score,
    approved_by, created_at, updated_at
)
VALUES

-- ── Remote: Tomcat 503 → LLM writes script → SSH execute on Linux ────────────
('20000000-0000-0000-0000-000000000013',
 '00000000-0000-0000-0000-000000000001',
 'Remote Tomcat Recovery (LLM + SSH)',
 'LLM generates a Bash script to stop and start Tomcat on a remote Linux server.
  Credentials are pulled from HashiCorp Vault. The script is uploaded via SFTP
  and executed; the remote file is deleted after completion.',
 'APPLICATION',
 '{"actions": [
     "CHECK_URL:http://app-server-01:8080/health",
     "REMOTE_EXEC:app-server-01:linux:Tomcat is returning 503 errors — stop it gracefully, wait 5 seconds, then start it again. Tomcat home is /opt/tomcat.",
     "CHECK_URL:http://app-server-01:8080/health:200"
  ],
  "rollback": [
     "REMOTE_EXEC:app-server-01:linux:Restart Tomcat immediately using CATALINA_HOME=/opt/tomcat"
  ],
  "description": "URL-probe → LLM SSH Tomcat fix → URL-verify"}',
 'v1.0', 'ACTIVE', 0.935, 'manager@democorp.com', NOW(), NOW()),

-- ── Remote: Windows IIS AppPool stuck → LLM writes PowerShell → SSH on Windows ─
('20000000-0000-0000-0000-000000000014',
 '00000000-0000-0000-0000-000000000001',
 'Remote IIS Application Pool Recovery (LLM + SSH)',
 'LLM generates a PowerShell script to recycle a stopped IIS application pool.
  The platform connects to the Windows server via SSH (OpenSSH) using Vault credentials.',
 'APPLICATION',
 '{"actions": [
     "CHECK_URL:http://win-app-01/health",
     "REMOTE_EXEC:win-app-01:windows:The IIS application pool named DefaultAppPool is stopped. Recycle it and verify the site responds.",
     "CHECK_URL:http://win-app-01/health:200"
  ],
  "rollback": [
     "REMOTE_EXEC:win-app-01:windows:Force stop all IIS application pools then restart them"
  ],
  "description": "URL-probe → LLM SSH IIS AppPool fix → URL-verify"}',
 'v1.0', 'ACTIVE', 0.910, 'manager@democorp.com', NOW(), NOW()),

-- ── Remote: PostgreSQL slow queries → LLM writes script → SSH ────────────────
('20000000-0000-0000-0000-000000000015',
 '00000000-0000-0000-0000-000000000001',
 'Remote DB Slow-Query Terminator (LLM + SSH)',
 'LLM generates a psql script to kill queries running longer than 60 seconds,
  reducing CPU and freeing connections. Executed remotely on the DB server.',
 'PERFORMANCE',
 '{"actions": [
     "REMOTE_EXEC:db-server-01:linux:PostgreSQL has queries running for over 60 seconds causing CPU spike. Connect to the database using psql as the postgres user and terminate all queries running longer than 60 seconds.",
     "CHECK_URL:http://app-server-01:8080/actuator/health:200"
  ],
  "rollback": [],
  "description": "LLM SSH terminate long-running PostgreSQL queries, re-verify app health"}',
 'v1.0', 'ACTIVE', 0.880, 'manager@democorp.com', NOW(), NOW()),

-- ── Remote: Redis memory high → LLM writes cache-clear script → SSH ──────────
('20000000-0000-0000-0000-000000000016',
 '00000000-0000-0000-0000-000000000001',
 'Remote Redis Cache Purge (LLM + SSH)',
 'LLM generates a script to flush Redis keys matching a pattern,
  then restarts the cache TTL policy without touching session keys.',
 'PERFORMANCE',
 '{"actions": [
     "REMOTE_EXEC:cache-server-01:linux:Redis memory usage is above 90%. Flush only the keys matching the prefix ''api_cache:*'' using redis-cli without flushing session or queue keys.",
     "CHECK_URL:http://app-server-01:8080/actuator/health:200"
  ],
  "rollback": [],
  "description": "LLM SSH targeted Redis key purge (api_cache:* only), re-verify app"}',
 'v1.0', 'ACTIVE', 0.870, 'manager@democorp.com', NOW(), NOW()),

-- ── Remote: Windows scheduled job failed → LLM writes PowerShell → SSH ───────
('20000000-0000-0000-0000-000000000017',
 '00000000-0000-0000-0000-000000000001',
 'Remote Windows Batch Job Rerun (LLM + SSH)',
 'LLM generates a PowerShell script to check and re-trigger a failed
  Windows Scheduled Task, capturing its last run status and output log.',
 'PERFORMANCE',
 '{"actions": [
     "REMOTE_EXEC:batch-server-01:windows:The Windows Scheduled Task named ''NightlyDataSync'' has failed. Check its last run status, clear any error state, and run it again using schtasks.",
     "REMOTE_EXEC:batch-server-01:windows:Verify the NightlyDataSync task completed successfully by reading its last run result from Task Scheduler."
  ],
  "rollback": [],
  "description": "LLM SSH check + rerun Windows NightlyDataSync task"}',
 'v1.0', 'ACTIVE', 0.850, 'manager@democorp.com', NOW(), NOW()),

-- ── Remote: Linux disk full → LLM writes log-cleanup script → SSH ────────────
('20000000-0000-0000-0000-000000000018',
 '00000000-0000-0000-0000-000000000001',
 'Remote Disk Space Recovery (LLM + SSH)',
 'LLM generates a Bash script to clean old log files and rotate logs,
  freeing disk space without deleting application data.',
 'INFRASTRUCTURE',
 '{"actions": [
     "REMOTE_EXEC:app-server-01:linux:The disk on /var is at 95% usage. Delete log files in /var/log/tomcat older than 7 days, compress log files older than 1 day, and report the space freed.",
     "CHECK_URL:http://app-server-01:8080/health:200"
  ],
  "rollback": [],
  "description": "LLM SSH log rotation and cleanup on /var/log/tomcat, verify app health"}',
 'v1.0', 'ACTIVE', 0.900, 'manager@democorp.com', NOW(), NOW())

ON CONFLICT (id) DO NOTHING;
