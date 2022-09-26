CREATE SCHEMA IF NOT EXISTS seedbank;

ALTER TABLE accession_collectors SET SCHEMA seedbank;
ALTER TABLE accession_photos SET SCHEMA seedbank;
ALTER TABLE accession_quantity_history SET SCHEMA seedbank;
ALTER TABLE accession_quantity_history_types SET SCHEMA seedbank;
ALTER TABLE accession_state_history SET SCHEMA seedbank;
ALTER TABLE accession_states SET SCHEMA seedbank;
ALTER TABLE accessions SET SCHEMA seedbank;
ALTER TABLE bags SET SCHEMA seedbank;
ALTER TABLE collection_sources SET SCHEMA seedbank;
ALTER TABLE data_sources SET SCHEMA seedbank;
ALTER TABLE geolocations SET SCHEMA seedbank;
ALTER TABLE processing_methods SET SCHEMA seedbank;
ALTER TABLE seed_quantity_units SET SCHEMA seedbank;
ALTER TABLE source_plant_origins SET SCHEMA seedbank;
ALTER TABLE storage_conditions SET SCHEMA seedbank;
ALTER TABLE storage_locations SET SCHEMA seedbank;
ALTER TABLE viability_test_results SET SCHEMA seedbank;
ALTER TABLE viability_test_seed_types SET SCHEMA seedbank;
ALTER TABLE viability_test_substrates SET SCHEMA seedbank;
ALTER TABLE viability_test_treatments SET SCHEMA seedbank;
ALTER TABLE viability_test_types SET SCHEMA seedbank;
ALTER TABLE viability_tests SET SCHEMA seedbank;
ALTER TABLE withdrawal_purposes SET SCHEMA seedbank;
ALTER TABLE withdrawals SET SCHEMA seedbank;

ALTER SEQUENCE accession_number_seq SET SCHEMA seedbank;
