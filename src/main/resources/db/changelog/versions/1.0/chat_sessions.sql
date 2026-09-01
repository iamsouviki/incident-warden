-- ── Chat Sessions & Message History ───────────────────────────────────

CREATE TABLE IF NOT EXISTS ai.chat_sessions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   VARCHAR(100) NOT NULL DEFAULT 'tenant-1',
    username    VARCHAR(100) NOT NULL,
    title       VARCHAR(255) NOT NULL DEFAULT 'New Conversation',
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    is_archived BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_chat_sessions_tenant_user ON ai.chat_sessions(tenant_id, username, updated_at DESC);

CREATE TABLE IF NOT EXISTS ai.chat_messages (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  UUID NOT NULL REFERENCES ai.chat_sessions(id) ON DELETE CASCADE,
    role        VARCHAR(50) NOT NULL,
    content     TEXT NOT NULL,
    metadata    JSONB,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chat_messages_session ON ai.chat_messages(session_id, created_at ASC);
