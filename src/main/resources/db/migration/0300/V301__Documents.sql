ALTER TABLE document_producer.pdds RENAME TO documents;
ALTER TABLE document_producer.pdd_statuses RENAME TO document_statuses;
ALTER TABLE document_producer.pdd_saved_versions RENAME TO document_saved_versions;

ALTER TABLE document_producer.document_saved_versions RENAME pdd_id TO document_id;
ALTER TABLE document_producer.variable_values RENAME pdd_id TO document_id;
