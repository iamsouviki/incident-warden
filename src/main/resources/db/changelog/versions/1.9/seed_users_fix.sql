-- ── Fix seed user hashes (verified BCrypt cost=10) ──────────────────────────
-- Upsert so this is idempotent on re-runs
INSERT INTO auth.users (username, email, password_hash, role, tenant_id, tenant_name)
VALUES
  ('admin',   'admin@mcp.local',   '$2a$10$W9jPu.BKQ7IFJoaE86m3Sun.d4qqKfD4gRd24EikE6Cjp5xbkh3f.', 'ADMIN',   'tenant-1', 'Primary Workspace'),
  ('analyst', 'analyst@mcp.local', '$2a$10$6XMZsalDITiFwWHX.2RlwOaLpFwAKAtUeoUvn83dVcr6aNuXO.JF6', 'ANALYST', 'tenant-1', 'Primary Workspace'),
  ('viewer',  'viewer@mcp.local',  '$2a$10$1e0Oof8hQepG6ceXkfLWOua2i0q33wMeFYYXcKr2xS669C1R3Vrr6', 'VIEWER',  'tenant-1', 'Primary Workspace')
ON CONFLICT (username) DO UPDATE
  SET password_hash = EXCLUDED.password_hash,
      role          = EXCLUDED.role;
