-- Positions of child variables are determined by manifest positions.
ALTER TABLE variable_sections DROP COLUMN position;
ALTER TABLE variable_table_columns DROP COLUMN position;
