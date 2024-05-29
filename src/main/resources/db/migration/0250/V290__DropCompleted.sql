DROP VIEW document_producer.variable_current_values;

ALTER TABLE document_producer.variable_values DROP COLUMN completed_time;

CREATE VIEW document_producer.variable_current_values AS
    SELECT DISTINCT ON (variable_id, index)
        id,
        pdd_id,
        variable_id,
        variable_type_id,
        index,
        created_by,
        created_time,
        number_value,
        text_value,
        date_value
    FROM document_producer.variable_values
    ORDER BY variable_id, index, id DESC;
