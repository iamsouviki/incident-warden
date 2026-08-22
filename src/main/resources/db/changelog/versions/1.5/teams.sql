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

-- No default teams or employees seeded. Teams and members are created and managed by administrators through the UI or API.
