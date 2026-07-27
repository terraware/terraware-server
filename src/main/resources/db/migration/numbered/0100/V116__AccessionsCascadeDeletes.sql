-- The foreign key constraints on various children of the accessions table didn't enable cascading
-- deletes because originally we didn't allow deleting accessions at all. Replace them with versions
-- that cascade deletes so we don't have to explicitly clean them up.
--
-- The constraint on accession_photos remains as-is because that table contains references to
-- the actual photo files outside the database; we need to explicitly delete those before we can
-- remove the references to them in our database or we'll accumulate orphaned files.

ALTER TABLE accession_state_history DROP CONSTRAINT accession_state_history_accession_id_fkey;
ALTER TABLE accession_state_history ADD CONSTRAINT accession_state_history_accession_id_fkey
    FOREIGN KEY (accession_id) REFERENCES accessions (id) ON DELETE CASCADE;

ALTER TABLE bags DROP CONSTRAINT bag_accession_id_fkey;
ALTER TABLE bags ADD CONSTRAINT bags_accession_id_fkey
    FOREIGN KEY (accession_id) REFERENCES accessions (id) ON DELETE CASCADE;

ALTER TABLE geolocations DROP CONSTRAINT collection_event_accession_id_fkey;
ALTER TABLE geolocations ADD CONSTRAINT geolocations_accession_id_fkey
    FOREIGN KEY (accession_id) REFERENCES accessions (id) ON DELETE CASCADE;

ALTER TABLE viability_test_selections DROP CONSTRAINT viability_test_selections_accession_id_fkey;
ALTER TABLE viability_test_selections ADD CONSTRAINT viability_test_selections_accession_id_fkey
    FOREIGN KEY (accession_id) REFERENCES accessions (id) ON DELETE CASCADE;

ALTER TABLE viability_test_results DROP CONSTRAINT viability_test_results_test_id_fkey;
ALTER TABLE viability_test_results ADD CONSTRAINT viability_test_results_test_id_fkey
    FOREIGN KEY (test_id) REFERENCES viability_tests (id) ON DELETE CASCADE;

ALTER TABLE viability_tests DROP CONSTRAINT viability_tests_accession_id_fkey;
ALTER TABLE viability_tests ADD CONSTRAINT viability_tests_accession_id_fkey
    FOREIGN KEY (accession_id) REFERENCES accessions (id) ON DELETE CASCADE;

ALTER TABLE withdrawals DROP CONSTRAINT withdrawal_accession_id_fkey;
ALTER TABLE withdrawals ADD CONSTRAINT withdrawals_accession_id_fkey
    FOREIGN KEY (accession_id) REFERENCES accessions (id) ON DELETE CASCADE;
