TRUNCATE vector_store;

ALTER TABLE vector_store DROP COLUMN embedding;
ALTER TABLE vector_store ADD COLUMN embedding halfvec(3072) NOT NULL;
CREATE INDEX vector_store_embedding_idx ON vector_store USING HNSW (embedding halfvec_cosine_ops);
