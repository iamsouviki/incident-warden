-- ──────────────────────────────────────────────────────────────────────────────
-- V7 — Realistic action plans for SOP procedures
--
-- Replaces the generic placeholder action strings with real tool invocations
-- that RemediationToolRegistry can actually execute:
--
--   CHECK_URL:<url>                            HTTP GET health probe
--   RESTART_SERVICE:<name>                     systemctl restart (Linux)
--   RESTART_SERVICE:<name>:CATALINA=<path>     Tomcat shutdown.sh + startup.sh
--   RESTART_SERVICE:<name>:windows-service     sc stop / sc start
--   CLEAR_CACHE:redis[:<host>:<port>[:<pattern>]]
--   CLEAR_CACHE:memcached:<host>:<port>
--   RERUN_JOB:<script.sh>                      /bin/sh <script>
--   RERUN_JOB:<taskname>:windows               schtasks /run /tn
--   RERUN_JOB:<job>:jenkins:<url>              Jenkins POST
--   SCALE_UP:<deployment>:<replicas>           kubectl scale
--   ROLLBACK_DEPLOY:<release>                  helm rollback
--   DRAIN_QUEUE:redis-list:<key>               redis-cli DEL
--
-- The URL-check → restart → URL-check pattern is the standard way to
-- verify an endpoint, fix it, and confirm recovery:
--   ["CHECK_URL:http://app/health",
--    "RESTART_SERVICE:tomcat",
--    "CHECK_URL:http://app/health:200"]
-- ──────────────────────────────────────────────────────────────────────────────

-- ── 1. DATABASE: full Tomcat + DB restart with URL health checks ─────────────
UPDATE sop_procedures
SET action_plan_json =
    '{"actions": [
        "CHECK_URL:http://localhost:5432/health",
        "RESTART_SERVICE:postgresql",
        "CLEAR_CACHE:redis:localhost:6379",
        "CHECK_URL:http://localhost:5432/health:200"
    ],
    "rollback": [
        "RESTART_SERVICE:postgresql"
    ],
    "description": "Check DB health, restart postgres, flush connection cache, re-verify"}'
WHERE id = '20000000-0000-0000-0000-000000000001';

-- ── 2. DATABASE performance: flush caches + rerun maintenance job ────────────
UPDATE sop_procedures
SET action_plan_json =
    '{"actions": [
        "CHECK_URL:http://localhost:8080/actuator/health",
        "CLEAR_CACHE:redis:localhost:6379",
        "RERUN_JOB:/opt/scripts/kill-slow-queries.sh",
        "CHECK_URL:http://localhost:8080/actuator/health:200"
    ],
    "rollback": [],
    "description": "Health-check app, flush Redis, rerun slow-query killer script, re-verify"}'
WHERE id = '20000000-0000-0000-0000-000000000002';

-- ── 3. INFRASTRUCTURE / CPU spike: scale up + kill runaway proc ──────────────
UPDATE sop_procedures
SET action_plan_json =
    '{"actions": [
        "CHECK_URL:http://localhost:8080/actuator/health",
        "SCALE_UP:api-deployment:5",
        "RERUN_JOB:/opt/scripts/kill-runaway-processes.sh",
        "CHECK_URL:http://localhost:8080/actuator/health:200"
    ],
    "rollback": [
        "SCALE_UP:api-deployment:2"
    ],
    "description": "Verify endpoint, scale up replicas, kill hot processes, re-verify"}'
WHERE id = '20000000-0000-0000-0000-000000000003';

-- ── 4. MEMORY exhaustion: flush caches → restart service → verify ────────────
UPDATE sop_procedures
SET action_plan_json =
    '{"actions": [
        "CHECK_URL:http://localhost:8080/actuator/health",
        "CLEAR_CACHE:redis:localhost:6379",
        "CLEAR_CACHE:memcached:localhost:11211",
        "RESTART_SERVICE:api-server",
        "CHECK_URL:http://localhost:8080/actuator/health:200"
    ],
    "rollback": [
        "RESTART_SERVICE:api-server"
    ],
    "description": "Health check, flush Redis + Memcached, restart service, confirm recovery"}'
WHERE id = '20000000-0000-0000-0000-000000000004';

-- ── 5. DEPLOYMENT rollback: check → rollback → verify ───────────────────────
UPDATE sop_procedures
SET action_plan_json =
    '{"actions": [
        "CHECK_URL:http://localhost:8080/actuator/health",
        "ROLLBACK_DEPLOY:api-release",
        "CHECK_URL:http://localhost:8080/actuator/health:200",
        "CHECK_URL:http://localhost:8080/api/v1/health:200"
    ],
    "rollback": [],
    "description": "Confirm bad deploy, helm rollback, double-verify both endpoints"}'
WHERE id = '20000000-0000-0000-0000-000000000005';

-- ── 6. NETWORK: verify → restart proxy → re-verify ──────────────────────────
UPDATE sop_procedures
SET action_plan_json =
    '{"actions": [
        "CHECK_URL:http://localhost:80/health",
        "RESTART_SERVICE:nginx",
        "RESTART_SERVICE:envoy",
        "CHECK_URL:http://localhost:80/health:200"
    ],
    "rollback": [
        "RESTART_SERVICE:nginx"
    ],
    "description": "Probe proxy endpoint, restart nginx + envoy, confirm healthy response"}'
WHERE id = '20000000-0000-0000-0000-000000000006';

-- ══════════════════════════════════════════════════════════════════════════════
-- NEW SOPs for Tomcat / Windows / Linux job patterns
-- ══════════════════════════════════════════════════════════════════════════════

INSERT INTO sop_procedures (
    id, tenant_id, title, description, category,
    action_plan_json, version, status, reliability_score,
    approved_by, created_at, updated_at
)
VALUES

-- ── 7. Tomcat 503 / URL health-fail → stop Tomcat → start → re-check ────────
('20000000-0000-0000-0000-000000000007',
 '00000000-0000-0000-0000-000000000001',
 'Tomcat Service Recovery (Linux CATALINA_HOME)',
 'Standard SOP for recovering a Tomcat web server that is returning 5xx or not responding.
  Step 1: HTTP health probe to confirm failure.
  Step 2: Stop Tomcat via shutdown.sh.
  Step 3: Start Tomcat via startup.sh.
  Step 4: Probe URL again to confirm 200 OK.',
 'APPLICATION',
 '{"actions": [
     "CHECK_URL:http://localhost:8080/health",
     "RESTART_SERVICE:tomcat:CATALINA=/opt/tomcat",
     "CHECK_URL:http://localhost:8080/health:200"
  ],
  "rollback": [
     "RESTART_SERVICE:tomcat:CATALINA=/opt/tomcat"
  ],
  "description": "Probe URL, stop/start Tomcat via CATALINA_HOME, re-verify 200"}',
 'v1.0', 'ACTIVE', 0.940, 'manager@democorp.com', NOW(), NOW()),

-- ── 8. Windows IIS / Windows Service restart + URL check ────────────────────
('20000000-0000-0000-0000-000000000008',
 '00000000-0000-0000-0000-000000000001',
 'Windows Application Service Recovery (sc stop/start)',
 'SOP for recovering a Windows-hosted application service that is not responding.
  Step 1: HTTP health probe.
  Step 2: sc stop the service.
  Step 3: sc start the service.
  Step 4: HTTP health probe to confirm recovery.',
 'APPLICATION',
 '{"actions": [
     "CHECK_URL:http://localhost:8080/health",
     "RESTART_SERVICE:MyAppService:windows-service",
     "CHECK_URL:http://localhost:8080/health:200"
  ],
  "rollback": [
     "RESTART_SERVICE:MyAppService:windows-service"
  ],
  "description": "Windows sc stop/start pattern with pre- and post-check"}',
 'v1.0', 'ACTIVE', 0.910, 'manager@democorp.com', NOW(), NOW()),

-- ── 9. Redis cache poisoning / OOM: flush + verify ───────────────────────────
('20000000-0000-0000-0000-000000000009',
 '00000000-0000-0000-0000-000000000001',
 'Redis Cache Flush and Recovery',
 'SOP to recover from Redis OOM eviction storm or cache poisoning.
  Step 1: Check app health endpoint.
  Step 2: FLUSHDB on Redis primary.
  Step 3: Optionally restart app to warm new caches.
  Step 4: Re-verify app health.',
 'PERFORMANCE',
 '{"actions": [
     "CHECK_URL:http://localhost:8080/actuator/health",
     "CLEAR_CACHE:redis:localhost:6379",
     "RESTART_SERVICE:api-server",
     "CHECK_URL:http://localhost:8080/actuator/health:200"
  ],
  "rollback": [
     "RESTART_SERVICE:api-server"
  ],
  "description": "Flush Redis FLUSHDB, restart app, re-verify"}',
 'v1.1', 'ACTIVE', 0.900, 'manager@democorp.com', NOW(), NOW()),

-- ── 10. Linux nightly job failure: rerun script + verify ─────────────────────
('20000000-0000-0000-0000-000000000010',
 '00000000-0000-0000-0000-000000000001',
 'Linux Batch Job Re-execution',
 'SOP for rerunning a failed Linux batch/cron job.
  Step 1: Execute the failed script directly.
  Step 2: Check app health to confirm side-effects resolved.',
 'PERFORMANCE',
 '{"actions": [
     "RERUN_JOB:/opt/scripts/nightly-cleanup.sh",
     "RERUN_JOB:/opt/scripts/rebuild-indexes.sh",
     "CHECK_URL:http://localhost:8080/actuator/health:200"
  ],
  "rollback": [],
  "description": "Re-run Linux shell scripts, then verify app health"}',
 'v1.0', 'ACTIVE', 0.850, 'manager@democorp.com', NOW(), NOW()),

-- ── 11. Windows scheduled task failure: schtasks trigger + verify ────────────
('20000000-0000-0000-0000-000000000011',
 '00000000-0000-0000-0000-000000000001',
 'Windows Scheduled Task Re-run',
 'SOP for re-triggering a failed Windows Task Scheduler job.
  Step 1: schtasks /run the named scheduled task.
  Step 2: Verify application endpoint after job completes.',
 'PERFORMANCE',
 '{"actions": [
     "RERUN_JOB:NightlyDataSync:windows",
     "RERUN_JOB:CacheWarmup:windows",
     "CHECK_URL:http://localhost:8080/actuator/health:200"
  ],
  "rollback": [],
  "description": "Windows schtasks /run for NightlyDataSync and CacheWarmup, then re-verify"}',
 'v1.0', 'ACTIVE', 0.830, 'manager@democorp.com', NOW(), NOW()),

-- ── 12. Kubernetes pod crash: rollout undo + URL verify ──────────────────────
('20000000-0000-0000-0000-000000000012',
 '00000000-0000-0000-0000-000000000001',
 'Kubernetes CrashLoopBackOff Recovery',
 'SOP for K8s pods stuck in CrashLoopBackOff.
  Step 1: kubectl rollout undo the deployment.
  Step 2: Verify endpoint responds 200.',
 'DEPLOYMENT',
 '{"actions": [
     "CHECK_URL:http://api-service.svc.cluster.local/health",
     "ROLLBACK_DEPLOY:api-deployment:kubectl",
     "CHECK_URL:http://api-service.svc.cluster.local/health:200"
  ],
  "rollback": [],
  "description": "kubectl rollout undo with pre- and post-probe"}',
 'v1.0', 'ACTIVE', 0.920, 'manager@democorp.com', NOW(), NOW())

ON CONFLICT (id) DO NOTHING;
