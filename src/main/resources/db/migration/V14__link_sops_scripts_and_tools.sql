-- V14: Link SOPs to generated scripts and MCP custom tools

ALTER TABLE sop_procedures
    ADD COLUMN IF NOT EXISTS linked_tool_id UUID REFERENCES custom_tools(id),
    ADD COLUMN IF NOT EXISTS linked_tool_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS linked_script_id UUID REFERENCES script_workspace(id),
    ADD COLUMN IF NOT EXISTS linked_script_name VARCHAR(200);

ALTER TABLE custom_tools
    ADD COLUMN IF NOT EXISTS script_workspace_id UUID REFERENCES script_workspace(id),
    ADD COLUMN IF NOT EXISTS sop_id UUID REFERENCES sop_procedures(id);

ALTER TABLE script_workspace
    ADD COLUMN IF NOT EXISTS sop_id UUID REFERENCES sop_procedures(id),
    ADD COLUMN IF NOT EXISTS tool_name VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_sop_procedures_linked_tool   ON sop_procedures(linked_tool_id);
CREATE INDEX IF NOT EXISTS idx_sop_procedures_linked_script ON sop_procedures(linked_script_id);
CREATE INDEX IF NOT EXISTS idx_custom_tools_script_workspace ON custom_tools(script_workspace_id);
CREATE INDEX IF NOT EXISTS idx_custom_tools_sop             ON custom_tools(sop_id);
CREATE INDEX IF NOT EXISTS idx_script_workspace_sop         ON script_workspace(sop_id);
