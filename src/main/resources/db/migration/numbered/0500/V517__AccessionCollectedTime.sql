ALTER TABLE seedbank.accessions
    ADD COLUMN collected_time TIMESTAMP WITH TIME ZONE;

WITH tz_data AS (
    SELECT
        a.id,
        COALESCE(u.time_zone, o.time_zone, 'UTC') AS time_zone
    FROM seedbank.accessions a
    JOIN users u ON u.id = a.created_by
    LEFT JOIN facilities f ON f.id = a.facility_id
    LEFT JOIN organizations o ON o.id = f.organization_id
)
UPDATE seedbank.accessions a
    SET collected_time = a.collected_date::TIMESTAMP AT TIME ZONE tz_data.time_zone
    FROM tz_data
    WHERE a.id = tz_data.id;
