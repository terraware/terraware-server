ALTER TABLE facilities ADD COLUMN facility_number INTEGER;

WITH facility_numbers AS (
    SELECT id, RANK() OVER (PARTITION BY organization_id, type_id ORDER BY id) AS facility_number
    FROM facilities
)
UPDATE facilities f
    SET facility_number = facility_numbers.facility_number
    FROM facility_numbers
    WHERE f.id = facility_numbers.id;

ALTER TABLE facilities ALTER COLUMN facility_number SET NOT NULL;
ALTER TABLE facilities ADD UNIQUE (organization_id, type_id, facility_number);
