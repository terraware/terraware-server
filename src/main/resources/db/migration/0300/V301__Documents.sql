ALTER TABLE pdds RENAME TO documents;
ALTER TABLE pdd_statuses RENAME TO document_statuses;
ALTER TABLE pdd_saved_versions RENAME TO document_saved_versions;

ALTER TABLE document_saved_versions RENAME pdd_id TO document_id;
ALTER TABLE variable_values RENAME pdd_id TO document_id;
