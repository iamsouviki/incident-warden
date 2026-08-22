-- Test-only bootstrap for the H2 context smoke test.
--
-- H2 has no JSONB type (VectorStoreEntity.metadata declares columnDefinition = "jsonb"),
-- and Hibernate will not create the schemas that entities are annotated into before it
-- creates the tables. Both are Postgres-native in the real application; this file exists
-- only so the wiring test can boot without a database server.
CREATE DOMAIN IF NOT EXISTS JSONB AS JSON;
CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS incident;
CREATE SCHEMA IF NOT EXISTS config;
CREATE SCHEMA IF NOT EXISTS teams;
CREATE SCHEMA IF NOT EXISTS tools;
CREATE SCHEMA IF NOT EXISTS sop;
