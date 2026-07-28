-- Update some column and table names to match what the concepts ended up being called in the
-- seed bank app user interface, and get rid of some unused schema elements.

UPDATE accession
SET environmental_notes = collection_site_notes
WHERE environmental_notes IS NULL;

ALTER TABLE accession DROP COLUMN collection_site_notes;
ALTER TABLE accession DROP COLUMN germination_testing;

ALTER TABLE accession RENAME COLUMN collection_trees TO trees_collected_from;
ALTER TABLE accession RENAME COLUMN founder_tree TO founder_id;

ALTER TABLE bag RENAME COLUMN label TO bag_number;

ALTER TABLE collection_event RENAME TO geolocation;

DROP TABLE collection_photo;

ALTER TABLE species DROP COLUMN target_frozen_humidity;
ALTER TABLE species DROP COLUMN target_refrigerated_humidity;
