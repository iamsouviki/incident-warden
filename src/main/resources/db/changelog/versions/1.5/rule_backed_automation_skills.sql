ALTER TABLE tools.skills ADD COLUMN IF NOT EXISTS definition_json TEXT;

INSERT INTO tools.skills (kind, skill_key, pattern, action_key, arg_count, mutating, enabled, description, definition_json)
SELECT 'CATEGORIZATION', 'POG_ISSUE',
       'POG MISSING,NOT ABLE PRINT LEBELS,NOT ABLE TO PRINT LABELS,PLANOGRAM ISSUE,SHELF TAG NOT GENERATING',
       'RESTART_SERVICE:pos-agent:linux', 2, TRUE, TRUE,
       'Planogram and shelf label generation/printing errors.',
       '{"script_path":"scripts/POGISSUEINCIDENTS.PS1","can_automate":true,"success_status":"Resolved","failure_status":"Escalated","failure_route":"ESCALATE_L2_STORE_OPS","duplicate_route":"ESCALATE_L3_MERCHANDISING_DEV","file_missing_route":"ESCALATE_L2_STORE_OPS"}'
WHERE NOT EXISTS (SELECT 1 FROM tools.skills WHERE kind = 'CATEGORIZATION' AND skill_key = 'POG_ISSUE');

INSERT INTO tools.skills (kind, skill_key, pattern, action_key, arg_count, mutating, enabled, description, definition_json)
SELECT 'EXTRACTION', 'POG_ISSUE', '', '', 0, TRUE, TRUE,
       'Mandatory and optional fields for POG incidents.',
       '{"fields":[{"key":"StoreNumber","label":"Store number","type":"text","required":true,"pattern":"(?i)\\b(?:store\\s*(?:number|no\\.?|#)?\\s*[:=#-]?\\s*)(\\d{1,6})\\b","placeholder":"e.g. 4022"},{"key":"PogLocation","label":"POG location","type":"text","required":true,"pattern":"(?i)\\b(\\d{1,3}[-/]\\d{2,5}[-/][A-Za-z0-9]+)\\b","placeholder":"e.g. 4-800-U"},{"key":"LabelPrintIssueFlag","label":"Label printing issue","type":"boolean","required":true,"default":"false","pattern":"(?i)\\b(label|shelf tag|print)\\w*\\b","placeholder":"true or false"},{"key":"OldPogFlag","label":"Old POG","type":"boolean","required":true,"default":"false","pattern":"(?i)\\b(old pog|previous planogram|discontinued layout|reset old version|outdated pog)\\b","placeholder":"true or false"},{"key":"Skulist","label":"SKU list","type":"text","required":false,"pattern":"(?i)\\b(?:sku|sku list|skulist)\\s*[:#=-]?\\s*([0-9][0-9, ]*)","placeholder":"e.g. 123456,789012"}]}'
WHERE NOT EXISTS (SELECT 1 FROM tools.skills WHERE kind = 'EXTRACTION' AND skill_key = 'POG_ISSUE');

INSERT INTO sop.sop_procedure (id, sop_id, step_number, title, description, match_keywords, action_key,
                               approval_status, requires_approval, execution_order, reliability)
SELECT gen_random_uuid(), 'POG_ISSUE', 1, 'POG incident remediation',
       'Approved POG issue automation. Use only after the declared fields are present.',
       'POG MISSING,NOT ABLE PRINT LEBELS,NOT ABLE TO PRINT LABELS,PLANOGRAM ISSUE,SHELF TAG NOT GENERATING',
       'RESTART_SERVICE:pos-agent:linux', 'APPROVED', TRUE, 10, 0.70
WHERE NOT EXISTS (SELECT 1 FROM sop.sop_procedure WHERE sop_id = 'POG_ISSUE' AND step_number = 1);
