CREATE TABLE IF NOT EXISTS incident.incident_comments (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL,
    author VARCHAR(255) NOT NULL,
    comment_text TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS incident.incident_history (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL,
    field_name VARCHAR(100) NOT NULL,
    old_value TEXT,
    new_value TEXT,
    updated_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_comments_incident ON incident.incident_comments(incident_id);
CREATE INDEX IF NOT EXISTS idx_history_incident ON incident.incident_history(incident_id);
