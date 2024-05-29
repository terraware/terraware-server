ALTER TABLE document_producer.variable_manifest_entries ADD COLUMN stable_id TEXT;
ALTER TABLE document_producer.variable_manifest_entries ADD UNIQUE (variable_manifest_id, stable_id);

-- Use fake stable IDs for manually-inserted test manifest entries.
UPDATE document_producer.variable_manifest_entries SET stable_id = variable_id::text WHERE stable_id IS NULL;

ALTER TABLE document_producer.variable_manifest_entries ALTER COLUMN stable_id SET NOT NULL;
