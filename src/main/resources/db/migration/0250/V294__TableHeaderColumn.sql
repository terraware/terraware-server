ALTER TABLE document_producer.variable_table_columns ADD COLUMN is_header BOOLEAN;
UPDATE document_producer.variable_table_columns SET is_header = FALSE;
ALTER TABLE document_producer.variable_table_columns ALTER COLUMN is_header SET NOT NULL;
