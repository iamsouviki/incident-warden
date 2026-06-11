-- Enable pgvector extension if it exists, but don't fail if it's missing immediately
-- It might be installed manually by the admin.
CREATE EXTENSION IF NOT EXISTS vector SCHEMA public;

-- DDL for vector_store if needed (though Spring AI handles this automatically)
CREATE TABLE IF NOT EXISTS vector_store (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    content text,
    metadata jsonb,
    embedding vector(768)
);
