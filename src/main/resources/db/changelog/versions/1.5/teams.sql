CREATE TABLE IF NOT EXISTS incident.teams (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT
);

CREATE TABLE IF NOT EXISTS incident.team_employees (
    id UUID PRIMARY KEY,
    team_id UUID NOT NULL REFERENCES incident.teams(id) ON DELETE CASCADE,
    username VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(255)
);

-- Insert sample teams
INSERT INTO incident.teams (id, name, description) VALUES
('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'IT Ops', 'Information Technology Operations'),
('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12', 'SecOps', 'Security Operations'),
('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13', 'Network Team', 'Network Infrastructure & Operations'),
('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a14', 'Database Admins', 'Database Administration and Support')
ON CONFLICT (name) DO NOTHING;

-- Insert sample employees
INSERT INTO incident.team_employees (id, team_id, username, email) VALUES
('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b11', 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'john_ops', 'john.ops@company.com'),
('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b12', 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'mary_ops', 'mary.ops@company.com'),
('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b13', 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12', 'alice_sec', 'alice.sec@company.com'),
('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b14', 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12', 'bob_sec', 'bob.sec@company.com'),
('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b15', 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13', 'net_nicole', 'nicole.net@company.com'),
('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b16', 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13', 'net_nathan', 'nathan.net@company.com'),
('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b17', 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a14', 'db_dan', 'dan.db@company.com'),
('b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b18', 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a14', 'db_debra', 'debra.db@company.com')
ON CONFLICT (username) DO NOTHING;
