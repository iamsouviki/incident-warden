-- 1.19 Add real name, role, and department metadata to users and team employees
ALTER TABLE auth.users ADD COLUMN IF NOT EXISTS full_name VARCHAR(255);
ALTER TABLE auth.users ADD COLUMN IF NOT EXISTS department VARCHAR(100);

ALTER TABLE teams.team_employees ADD COLUMN IF NOT EXISTS full_name VARCHAR(255);
ALTER TABLE teams.team_employees ADD COLUMN IF NOT EXISTS role VARCHAR(100);
ALTER TABLE teams.team_employees ADD COLUMN IF NOT EXISTS department VARCHAR(100);

-- Update seed admin user with proper human-readable name
UPDATE auth.users SET full_name = 'System Administrator', department = 'Operations' WHERE username = 'admin' AND (full_name IS NULL OR full_name = '');
