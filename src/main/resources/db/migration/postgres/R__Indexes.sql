-- Index some columns based on observation of query plans in both real-world and test environments.
-- Which indexes are useful can vary by database engine, and we want to be able to use indexing
-- features that aren't part of standard SQL, so these are treated as Postgres-specific.

-- Foreign key columns. Not all foreign keys are indexed, just ones where the index was observed
-- to make a difference in actual query execution.
CREATE INDEX IF NOT EXISTS collection_event__accession_id_ix ON collection_event (accession_id);
CREATE INDEX IF NOT EXISTS germination__test_id_ix ON germination (test_id);
CREATE INDEX IF NOT EXISTS germination_test__accession_id_ix ON germination_test (accession_id);
CREATE INDEX IF NOT EXISTS withdrawal__accession_id_ix ON withdrawal (accession_id);

-- Indexes for some columns that we expect to commonly be used to sort the search results and
-- where testing indicated that the database would actually use the indexes.
CREATE INDEX IF NOT EXISTS accession__received_date_ix ON accession (received_date);
CREATE INDEX IF NOT EXISTS withdrawal__date_ix ON withdrawal (date);
