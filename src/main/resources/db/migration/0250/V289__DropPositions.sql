-- Positions of child variables are determined by manifest positions.
ALTER TABLE document_producer.variable_sections DROP COLUMN position;
ALTER TABLE document_producer.variable_table_columns DROP COLUMN position;
