CREATE TABLE IF NOT EXISTS incident.statuses (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);

-- Insert default statuses
INSERT INTO incident.statuses (id, name) VALUES
('c0eebc99-9c0b-4ef8-bb6d-6bb9bd380c11', 'New'),
('c0eebc99-9c0b-4ef8-bb6d-6bb9bd380c12', 'In Progress'),
('c0eebc99-9c0b-4ef8-bb6d-6bb9bd380c13', 'Resolved'),
('c0eebc99-9c0b-4ef8-bb6d-6bb9bd380c14', 'Closed')
ON CONFLICT (name) DO NOTHING;
