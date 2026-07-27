CREATE TABLE accelerator.deliverable_variables(
    deliverable_id BIGINT NOT NULL REFERENCES accelerator.deliverables ON DELETE CASCADE,
    variable_id BIGINT NOT NULL REFERENCES docprod.variables ON DELETE CASCADE,
    position INTEGER NOT NULL,

    PRIMARY KEY (variable_id, deliverable_id)
);

INSERT INTO accelerator.deliverable_variables (deliverable_id, variable_id, position)
SELECT deliverable_id, id, deliverable_position
FROM docprod.variables
WHERE deliverable_id IS NOT NULL
AND deliverable_position IS NOT NULL;

ALTER TABLE docprod.variables DROP COLUMN deliverable_id;
ALTER TABLE docprod.variables DROP COLUMN deliverable_position;
