-- Migrate existing data from environmental_notes to collection_site_notes (code is already writing
-- to both columns) in preparation for dropping environmental_notes.
UPDATE accessions
SET collection_site_notes = environmental_notes
WHERE collection_site_notes IS NULL
AND environmental_notes IS NOT NULL;
