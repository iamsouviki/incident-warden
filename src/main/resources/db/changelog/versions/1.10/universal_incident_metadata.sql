ALTER TABLE incident.incidents
    ADD COLUMN IF NOT EXISTS category VARCHAR(100) NOT NULL DEFAULT 'General',
    ADD COLUMN IF NOT EXISTS confidence_score DOUBLE PRECISION NOT NULL DEFAULT 0;

ALTER TABLE incident.external_incidents
    ADD COLUMN IF NOT EXISTS category VARCHAR(100) NOT NULL DEFAULT 'General',
    ADD COLUMN IF NOT EXISTS confidence_score DOUBLE PRECISION NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_incidents_category ON incident.incidents(category);
CREATE INDEX IF NOT EXISTS idx_incidents_confidence ON incident.incidents(confidence_score);
CREATE INDEX IF NOT EXISTS idx_external_incidents_category ON incident.external_incidents(category);
CREATE INDEX IF NOT EXISTS idx_external_incidents_confidence ON incident.external_incidents(confidence_score);
