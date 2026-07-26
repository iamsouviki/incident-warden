CREATE TABLE IF NOT EXISTS incident.external_incidents (
    id UUID PRIMARY KEY,
    subject VARCHAR(255) NOT NULL,
    description TEXT,
    assignee VARCHAR(255),
    assigned_gteam VARCHAR(255),
    priority VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'New',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    due_date TIMESTAMP WITH TIME ZONE,
    external_source VARCHAR(50) NOT NULL,
    external_id VARCHAR(255) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ext_incidents_priority ON incident.external_incidents(priority);
CREATE INDEX IF NOT EXISTS idx_ext_incidents_status ON incident.external_incidents(status);
CREATE INDEX IF NOT EXISTS idx_ext_incidents_source ON incident.external_incidents(external_source);
