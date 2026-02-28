-- V3: Add missing columns to incidents table for agent pipeline results

ALTER TABLE incidents
    ADD COLUMN IF NOT EXISTS confidence_score DECIMAL(5,4),
    ADD COLUMN IF NOT EXISTS matched_sop_id UUID REFERENCES sop_procedures(id),
    ADD COLUMN IF NOT EXISTS matched_pattern_id UUID REFERENCES incident_patterns(id),
    ADD COLUMN IF NOT EXISTS pattern_similarity DECIMAL(5,4),
    ADD COLUMN IF NOT EXISTS final_confidence_score DECIMAL(5,4),
    ADD COLUMN IF NOT EXISTS risk_level VARCHAR(20),
    ADD COLUMN IF NOT EXISTS decided_by_human BOOLEAN DEFAULT false,
    ADD COLUMN IF NOT EXISTS decision_reason TEXT;
