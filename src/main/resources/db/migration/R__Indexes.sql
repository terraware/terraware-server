-- Index some columns based on observation of query plans in both real-world and test environments.
-- Which indexes are useful can vary by database engine, and we want to be able to use indexing
-- features that aren't part of standard SQL, so these are treated as Postgres-specific.

-- Foreign key columns. Not all foreign keys are indexed, just ones where the index was observed
-- to make a difference in actual query execution.
CREATE INDEX IF NOT EXISTS accessions__facility_id_ix ON seedbank.accessions (facility_id);
CREATE INDEX IF NOT EXISTS accessions__species_id_ix ON seedbank.accessions (species_id);
CREATE INDEX IF NOT EXISTS batches__species_id_ix ON nursery.batches (species_id);
CREATE INDEX IF NOT EXISTS geolocation__accession_id_ix ON seedbank.geolocations (accession_id);
CREATE INDEX IF NOT EXISTS plantings__species_id_ix ON tracking.plantings (species_id);
CREATE INDEX IF NOT EXISTS viability_test_results__test_id_ix ON seedbank.viability_test_results (test_id);
CREATE INDEX IF NOT EXISTS viability_test__accession_id_ix ON seedbank.viability_tests (accession_id);
CREATE INDEX IF NOT EXISTS withdrawal__accession_id_ix ON seedbank.withdrawals (accession_id);

-- Indexes for some columns that we expect to commonly be used to sort the search results and
-- where testing indicated that the database would actually use the indexes.
CREATE INDEX IF NOT EXISTS accession__received_date_ix ON seedbank.accessions (received_date);
CREATE INDEX IF NOT EXISTS withdrawal__date_ix ON seedbank.withdrawals (date);

-- Enable trigram support for fuzzy text search, and index all the text fields that are likely to
-- be frequently fuzzy-searched.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS accession__number_trgm ON seedbank.accessions USING gin (number gin_trgm_ops);
CREATE INDEX IF NOT EXISTS gbif_names__name_trgm ON gbif_names USING gin (name gin_trgm_ops);

-- Partial indexes.
CREATE INDEX IF NOT EXISTS species__not_checked_ix ON species (id) WHERE checked_time IS NULL;
