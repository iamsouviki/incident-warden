-- V6: Fix remaining JSONB columns to TEXT for JPA String mapping compatibility

-- hitl_requests.modifications (JSON object for approved modifications)
ALTER TABLE hitl_requests
    ALTER COLUMN modifications TYPE TEXT USING modifications::TEXT;

-- action_execution_log JSONB columns
ALTER TABLE action_execution_log
    ALTER COLUMN parameters TYPE TEXT USING parameters::TEXT;

ALTER TABLE action_execution_log
    ALTER COLUMN result TYPE TEXT USING result::TEXT;

ALTER TABLE action_execution_log
    ALTER COLUMN pre_state TYPE TEXT USING pre_state::TEXT;

ALTER TABLE action_execution_log
    ALTER COLUMN post_state TYPE TEXT USING post_state::TEXT;

-- sop_uploads.parsed_json
ALTER TABLE sop_uploads
    ALTER COLUMN parsed_json TYPE TEXT USING parsed_json::TEXT;
