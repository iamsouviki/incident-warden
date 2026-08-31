-- 1.22 — The three things the agent decides, as rows an admin can edit.
--
-- Before this, each stage of the pipeline was a constant in Java:
--   categorisation  AgentAssessmentService.classify()'s keyword ladder (printer / vpn / service)
--   extraction      IncidentTarget's HOST, LABELLED, BARE_FQDN regexes
--   execution       RemediationToolRegistry.TOOLS, a Map.of of four entries
--
-- So a workspace whose tills are called "lane" instead of "pos", or whose hostnames are
-- shaped "42-TILL-03", could not be supported without a redeploy — and the platform's own
-- rule is that everything is configured from the UI. One table, three kinds, because these
-- are the same shape of decision (text in, a decision out) and three tables would be three
-- pages, three controllers and three sets of guards to keep in step.
--
-- What each kind uses:
--   CATEGORIZATION  pattern = comma/space separated keywords; skill_key = the category
--                   (APPLICATION, NETWORK, ...); action_key = the action to propose
--   EXTRACTION      pattern = a regex whose FIRST capturing group is the value; skill_key
--                   names the field it fills (currently only targetHost is consumed)
--   EXECUTION       skill_key = the tool name; arg_count = segments required after it;
--                   mutating = whether it changes the host
--
-- Rows here widen what the platform can recognise. They cannot widen what it may do: a
-- skill is still parsed by RemediationToolRegistry, still scanned by GuardrailService,
-- still hash-pinned, and still refused without a human approval. mutating=false is the one
-- field that could quietly downgrade a restart into "safe", so SkillService writes it only
-- for an ADMIN and records the change in the audit trail.
CREATE SCHEMA IF NOT EXISTS tools;

CREATE TABLE IF NOT EXISTS tools.skills (
    id           UUID PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL DEFAULT 'tenant-1',
    kind         VARCHAR(24)  NOT NULL,
    skill_key    VARCHAR(120) NOT NULL,
    pattern      VARCHAR(600),
    action_key   VARCHAR(120),
    arg_count    INTEGER      NOT NULL DEFAULT 0,
    mutating     BOOLEAN      NOT NULL DEFAULT TRUE,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    description  VARCHAR(600),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by   VARCHAR(120) NOT NULL DEFAULT 'system',
    CONSTRAINT skills_kind CHECK (kind IN ('CATEGORIZATION', 'EXTRACTION', 'EXECUTION')),
    CONSTRAINT skills_unique UNIQUE (tenant_id, kind, skill_key)
);

CREATE INDEX IF NOT EXISTS skills_kind_enabled ON tools.skills (kind, enabled);

-- The four execution tools, moved out of Java verbatim. mutating and arg_count are
-- unchanged, so a plan that parsed before this migration parses identically after it.
INSERT INTO tools.skills (id, tenant_id, kind, skill_key, arg_count, mutating, description)
VALUES
  (gen_random_uuid(), 'tenant-1', 'EXECUTION', 'CHECK_URL',       2, FALSE, 'Probe an HTTP endpoint and compare the status code.'),
  (gen_random_uuid(), 'tenant-1', 'EXECUTION', 'RESTART_SERVICE', 2, TRUE,  'Restart a named OS service on one host.'),
  (gen_random_uuid(), 'tenant-1', 'EXECUTION', 'CLEAR_CACHE',     3, TRUE,  'Flush one cache tier.'),
  (gen_random_uuid(), 'tenant-1', 'EXECUTION', 'RERUN_JOB',       2, TRUE,  'Re-trigger one batch job.')
ON CONFLICT (tenant_id, kind, skill_key) DO NOTHING;

-- The categorisation vocabulary, also moved verbatim from classify()'s fallback ladder.
-- Order is the ladder order: the first enabled row whose keywords appear in the ticket wins.
INSERT INTO tools.skills (id, tenant_id, kind, skill_key, pattern, action_key, mutating, description)
VALUES
  (gen_random_uuid(), 'tenant-1', 'CATEGORIZATION', 'PRINTING', 'printer, print queue, print job',
   'clear-printer-queue', FALSE, 'Anything the store calls a printer problem.'),
  (gen_random_uuid(), 'tenant-1', 'CATEGORIZATION', 'NETWORK', 'vpn, wifi, network, router, switch',
   'refresh-network-session', FALSE, 'Connectivity at the store end.'),
  (gen_random_uuid(), 'tenant-1', 'CATEGORIZATION', 'APPLICATION',
   'service, daemon, application unavailable, tomcat, unresponsive, not responding, app is down, application is down, 502, hung, crashed',
   'restart-approved-service', FALSE, 'A process that is up but not answering, in the words a store manager uses.')
ON CONFLICT (tenant_id, kind, skill_key) DO NOTHING;

-- One extraction skill, seeded as the worked example rather than as a duplicate of the
-- built-in host regexes: those stay in IncidentTarget and still run first, because a
-- broken row must not be able to stop the platform finding a host it used to find.
INSERT INTO tools.skills (id, tenant_id, kind, skill_key, pattern, mutating, description)
VALUES
  (gen_random_uuid(), 'tenant-1', 'EXTRACTION', 'targetHost',
   '\b(\d{2,6}-(?:till|lane|pos)-\d{1,3})\b', FALSE,
   'Hostnames written store-number-first, e.g. 0042-TILL-03. Group 1 is the host.')
ON CONFLICT (tenant_id, kind, skill_key) DO NOTHING;
