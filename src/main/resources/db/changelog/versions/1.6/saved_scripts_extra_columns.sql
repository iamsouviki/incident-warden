-- Add missing columns to tools.saved_scripts for parameter definitions and dry-run validation
ALTER TABLE tools.saved_scripts ADD COLUMN IF NOT EXISTS required_input_data TEXT;
ALTER TABLE tools.saved_scripts ADD COLUMN IF NOT EXISTS validated_in_dry_run BOOLEAN DEFAULT TRUE;
