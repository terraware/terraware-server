ALTER TABLE document_producer.variable_select_options ADD COLUMN rendered_text TEXT;

CREATE TABLE document_producer.variable_table_styles (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE document_producer.variable_tables (
    variable_id BIGINT PRIMARY KEY,
    variable_type_id INTEGER NOT NULL REFERENCES document_producer.variable_types,
    table_style_id INTEGER NOT NULL REFERENCES document_producer.variable_table_styles,

    CHECK (variable_type_id = 6),

    FOREIGN KEY (variable_id, variable_type_id) REFERENCES document_producer.variables (id, variable_type_id) ON DELETE CASCADE
);

DROP VIEW document_producer.variable_current_values;

ALTER TABLE document_producer.variable_values DROP COLUMN verified_by;
ALTER TABLE document_producer.variable_values RENAME COLUMN verified_time TO completed_time;

CREATE VIEW document_producer.variable_current_values AS
    SELECT DISTINCT ON (variable_id, index)
        *
    FROM document_producer.variable_values
    ORDER BY variable_id, index, id DESC;

-- No longer need the "title required" flag which was the only attribute in this table.
DROP TABLE document_producer.variable_images;
