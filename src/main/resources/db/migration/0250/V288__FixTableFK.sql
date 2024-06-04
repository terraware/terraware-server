ALTER TABLE variable_table_columns DROP CONSTRAINT variable_table_columns_table_variable_id_table_variable_ty_fkey;

ALTER TABLE variable_tables ADD UNIQUE (variable_id, variable_type_id);

ALTER TABLE variable_table_columns ADD
    FOREIGN KEY (table_variable_id, table_variable_type_id)
    REFERENCES variable_tables (variable_id, variable_type_id)
    ON DELETE CASCADE;
