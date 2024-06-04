-- This can be null for text values.
ALTER TABLE variable_section_values ALTER COLUMN used_variable_type_id DROP NOT NULL;

-- Not using this view anywhere and it gets in the way of ALTER TABLE statements.
DROP VIEW variable_current_values;

-- Variable type ID needs to be an integer, not a bigint, so codegen will turn it into an enum.
ALTER TABLE variable_values ALTER COLUMN variable_type_id TYPE INTEGER;

ALTER TABLE variable_values ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE variable_values RENAME COLUMN index TO list_position;
