DROP TABLE document_producer.variable_value_citations;

ALTER TABLE document_producer.variable_values ADD COLUMN citation TEXT;
