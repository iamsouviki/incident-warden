-- Ensure schemas exist
CREATE SCHEMA IF NOT EXISTS mcp_rag;

-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- DDL for vector_store in public/mcp_rag (moved to sop schema in 1.7)
CREATE TABLE IF NOT EXISTS vector_store (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    content text,
    metadata jsonb,
    embedding vector(768)
);
