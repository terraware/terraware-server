ALTER TABLE variable_table_columns ADD COLUMN is_header BOOLEAN;
UPDATE variable_table_columns SET is_header = FALSE;
ALTER TABLE variable_table_columns ALTER COLUMN is_header SET NOT NULL;
