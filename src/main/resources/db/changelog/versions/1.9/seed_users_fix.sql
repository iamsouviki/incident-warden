-- ── Fix seed admin user hash ──────────────────────────
INSERT INTO auth.users (username, email, password_hash, role, tenant_id, tenant_name)
VALUES
  ('admin', 'admin@mcp.local', '$2a$10$W9jPu.BKQ7IFJoaE86m3Sun.d4qqKfD4gRd24EikE6Cjp5xbkh3f.', 'ADMIN', 'tenant-1', 'Primary Workspace')
ON CONFLICT (username) DO UPDATE
  SET password_hash = EXCLUDED.password_hash,
      role          = EXCLUDED.role;
