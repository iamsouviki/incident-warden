-- Create tsvector search column and index for FTS Hybrid Search
ALTER TABLE vector_store ADD COLUMN IF NOT EXISTS fts_vector tsvector;
CREATE INDEX IF NOT EXISTS vector_store_fts_idx ON vector_store USING gin(fts_vector);

-- Create trigger to automatically update fts_vector on content update
CREATE OR REPLACE FUNCTION vector_store_fts_trigger() RETURNS trigger AS $$
begin
  new.fts_vector := to_tsvector('english', coalesce(new.content, ''));
  return new;
end
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS tsvectorupdate ON vector_store;
CREATE TRIGGER tsvectorupdate BEFORE INSERT OR UPDATE ON vector_store
FOR EACH ROW EXECUTE FUNCTION vector_store_fts_trigger();

-- Populate existing rows
UPDATE vector_store SET fts_vector = to_tsvector('english', coalesce(content, ''));
