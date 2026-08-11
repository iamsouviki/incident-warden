ALTER TABLE IF EXISTS tools.execution_logs ADD COLUMN IF NOT EXISTS incident_id UUID;
ALTER TABLE IF EXISTS tools.execution_logs ADD COLUMN IF NOT EXISTS agent VARCHAR(80);
ALTER TABLE IF EXISTS tools.execution_logs ADD COLUMN IF NOT EXISTS phase VARCHAR(80);
ALTER TABLE IF EXISTS tools.execution_logs ADD COLUMN IF NOT EXISTS validation_status VARCHAR(40);
CREATE INDEX IF NOT EXISTS idx_execution_logs_incident ON tools.execution_logs(incident_id);

CREATE TABLE IF NOT EXISTS incident.telemetry_events (
    id UUID PRIMARY KEY,
    device_id VARCHAR(160) NOT NULL,
    store_id VARCHAR(160) NOT NULL,
    device_type VARCHAR(80),
    event_type VARCHAR(120) NOT NULL,
    severity VARCHAR(40),
    message TEXT,
    status VARCHAR(40),
    received_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS idx_telemetry_received ON incident.telemetry_events(received_at DESC);
CREATE INDEX IF NOT EXISTS idx_telemetry_device ON incident.telemetry_events(device_id, store_id);
