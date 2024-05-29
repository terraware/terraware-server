ALTER TABLE document_producer.variable_table_columns DROP CONSTRAINT variable_table_columns_table_variable_id_table_variable_ty_fkey;

ALTER TABLE document_producer.variable_tables ADD UNIQUE (variable_id, variable_type_id);

ALTER TABLE document_producer.variable_table_columns ADD
    FOREIGN KEY (table_variable_id, table_variable_type_id)
    REFERENCES document_producer.variable_tables (variable_id, variable_type_id)
    ON DELETE CASCADE;
