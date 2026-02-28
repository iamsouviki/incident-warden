-- V2: Sample data seeding for development and testing

-- SAMPLE TENANT
INSERT INTO tenants (id, name, plan, auto_resolve_threshold, hitl_threshold, allow_p1_auto_resolve, is_active, created_at)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'Demo Corp',
    'ENTERPRISE',
    1.000, 0.800, false, true, NOW()
) ON CONFLICT (id) DO NOTHING;

-- CLASSIFICATION RULES
INSERT INTO classification_rules (id, tenant_id, pattern, category, sub_category, severity, confidence, priority, is_active, created_at)
VALUES
    ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     '(?i).*(database|db|postgres|mysql|oracle).*(down|unavailable|connection.refused|timeout).*',
     'DATABASE', 'CONNECTIVITY', 'P1', 0.950, 1, true, NOW()),
    ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
     '(?i).*(slow.queries?|query.timeout|execution.time.*second|database.*performance).*',
     'DATABASE', 'PERFORMANCE', 'P2', 0.900, 2, true, NOW()),
    ('10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001',
     '(?i).*(disk.full|storage.*percent|tablespace.*full|filesystem.*100).*',
     'DATABASE', 'STORAGE', 'P2', 0.920, 2, true, NOW()),
    ('10000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001',
     '(?i).*(network|connectivity|packet.loss|latency|jitter).*(high|increased|spike|100%).*',
     'NETWORK', 'CONNECTIVITY', 'P2', 0.880, 3, true, NOW()),
    ('10000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000001',
     '(?i).*(ssl|tls|certificate).*(expired|invalid|error|failed).*',
     'NETWORK', 'SECURITY', 'P1', 0.950, 1, true, NOW()),
    ('10000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000001',
     '(?i).*(cpu.usage|cpu.*percent|processor.*load).*(high|spike|100|90|95).*',
     'INFRASTRUCTURE', 'RESOURCE', 'P2', 0.900, 3, true, NOW()),
    ('10000000-0000-0000-0000-000000000007', '00000000-0000-0000-0000-000000000001',
     '(?i).*(memory|ram|heap|oom|out.of.memory).*(high|full|exhausted|leak|error).*',
     'INFRASTRUCTURE', 'RESOURCE', 'P2', 0.900, 3, true, NOW()),
    ('10000000-0000-0000-0000-000000000008', '00000000-0000-0000-0000-000000000001',
     '(?i).*(deployment|deploy|release|rollout).*(failed|stuck|error|timeout).*',
     'DEPLOYMENT', 'ROLLOUT', 'P2', 0.880, 4, true, NOW()),
    ('10000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000001',
     '(?i).*(pod.crashloop|container.restart|CrashLoopBackOff|ImagePullBackOff).*',
     'DEPLOYMENT', 'KUBERNETES', 'P1', 0.950, 1, true, NOW()),
    ('10000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000001',
     '(?i).*(http.5[0-9]{2}|500.error|502.bad.gateway|503.service.unavailable|504.gateway).*',
     'APPLICATION', 'HTTP_ERROR', 'P2', 0.920, 2, true, NOW())
ON CONFLICT (id) DO NOTHING;

-- SOP PROCEDURES
INSERT INTO sop_procedures (
    id, tenant_id, title, description, category,
    action_plan_json, version, status, reliability_score,
    approved_by, created_at, updated_at
)
VALUES
    ('20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'Database Service Recovery',
     'Procedure for recovering a failed database service. Step 1: Check database process status. Step 2: Review error logs. Step 3: Restart database service. Step 4: Verify connections restored.',
     'DATABASE',
     '{"actions": ["RESTART_SERVICE:postgresql", "CLEAR_CACHE:pgbouncer"], "validation": ["HEALTH_CHECK:db-connections"]}',
     'v1.0', 'ACTIVE', 0.920, 'manager@democorp.com', NOW(), NOW()),

    ('20000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
     'Database Performance Optimization',
     'Procedure for handling database performance degradation. Step 1: Identify slow queries. Step 2: Kill long-running queries. Step 3: Add missing indexes.',
     'DATABASE',
     '{"actions": ["CLEAR_CACHE:query-cache", "RUN_SCRIPT:kill-slow-queries"]}',
     'v1.2', 'ACTIVE', 0.880, 'manager@democorp.com', NOW(), NOW()),

    ('20000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001',
     'High CPU Usage Resolution',
     'Procedure for resolving high CPU usage. Step 1: Identify CPU consuming processes. Step 2: Scale up replicas. Step 3: Monitor post-action CPU.',
     'INFRASTRUCTURE',
     '{"actions": ["SCALE_UP:api-server:5", "RUN_SCRIPT:kill-runaway-processes"]}',
     'v1.0', 'ACTIVE', 0.850, 'manager@democorp.com', NOW(), NOW()),

    ('20000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001',
     'Memory Exhaustion Recovery',
     'Procedure for handling memory exhaustion. Step 1: Clear caches. Step 2: Restart affected services. Step 3: Monitor memory recovery.',
     'INFRASTRUCTURE',
     '{"actions": ["CLEAR_CACHE:all", "RESTART_SERVICE:api-server"]}',
     'v1.1', 'ACTIVE', 0.820, 'manager@democorp.com', NOW(), NOW()),

    ('20000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000001',
     'Emergency Deployment Rollback',
     'Emergency rollback for failed deployments. Step 1: Identify failing deployment. Step 2: Execute rollback. Step 3: Verify previous version.',
     'DEPLOYMENT',
     '{"actions": ["ROLLBACK_DEPLOY:latest"], "validation": ["HEALTH_CHECK:api-health"]}',
     'v1.3', 'ACTIVE', 0.950, 'manager@democorp.com', NOW(), NOW()),

    ('20000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000001',
     'Network Connectivity Recovery',
     'Procedure for resolving network connectivity issues. Step 1: Verify network. Step 2: Restart proxy. Step 3: Verify connectivity.',
     'NETWORK',
     '{"actions": ["RESTART_SERVICE:nginx", "RESTART_SERVICE:service-mesh"]}',
     'v1.0', 'ACTIVE', 0.870, 'manager@democorp.com', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- INCIDENT PATTERNS
INSERT INTO incident_patterns (
    id, tenant_id, name, description, category,
    avg_resolution_minutes, reliability_score,
    occurrence_count, success_count, is_active, created_at
)
VALUES
    ('30000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'Database Service Failure',
     'Database service becomes unavailable due to process crash, connection limit exceeded, or resource exhaustion',
     'DATABASE', 15, 0.900, 50, 42, true, NOW()),
    ('30000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
     'Database Slow Query Degradation',
     'Database queries slow due to missing indexes, table bloat, or query plan regression',
     'DATABASE', 45, 0.820, 30, 22, true, NOW()),
    ('30000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001',
     'Service CPU Spike',
     'Service CPU usage spikes due to runaway processes, increased traffic, or infinite loops',
     'INFRASTRUCTURE', 10, 0.880, 40, 35, true, NOW()),
    ('30000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001',
     'Memory Leak Detection',
     'Service memory usage grows continuously until OOM event',
     'INFRASTRUCTURE', 20, 0.780, 25, 19, true, NOW()),
    ('30000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000001',
     'Deployment Rollout Failure',
     'New deployment fails and causes service disruption',
     'DEPLOYMENT', 5, 0.920, 60, 57, true, NOW()),
    ('30000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000001',
     'Network Service Degradation',
     'Network connectivity issues between services',
     'NETWORK', 12, 0.850, 35, 29, true, NOW())
ON CONFLICT (id) DO NOTHING;

-- PATTERN-TO-SOP LINKS
INSERT INTO pattern_sop_links (id, pattern_id, sop_id)
VALUES
    ('40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001'),
    ('40000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000002'),
    ('40000000-0000-0000-0000-000000000003', '30000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000003'),
    ('40000000-0000-0000-0000-000000000004', '30000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000004'),
    ('40000000-0000-0000-0000-000000000005', '30000000-0000-0000-0000-000000000005', '20000000-0000-0000-0000-000000000005'),
    ('40000000-0000-0000-0000-000000000006', '30000000-0000-0000-0000-000000000006', '20000000-0000-0000-0000-000000000006')
ON CONFLICT (id) DO NOTHING;
