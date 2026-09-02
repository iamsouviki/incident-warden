-- Credentials belong in the environment, not in a table that every admin read touches.
-- IntegrationManagerService now reads MCP_SERVICENOW_PASSWORD / MCP_FRESHSERVICE_API_KEY /
-- MCP_JIRA_API_TOKEN and never writes them back, so any row left here is a live secret with
-- no reader. Delete it.
--
-- Set the replacements before deploying, or the ITSM sync authenticates with a blank
-- credential and the vendor returns 401 (which is the visible failure we want, not a silent one).

DELETE FROM config.system_config
 WHERE config_key IN ('servicenow_password', 'freshservice_api_key', 'jira_api_token');
