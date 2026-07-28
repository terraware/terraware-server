INSERT INTO tracking.observation_requested_subzones (observation_id, planting_subzone_id)
SELECT o.id, sz.id
FROM tracking.observations o
JOIN tracking.planting_subzones sz ON o.planting_site_id = sz.planting_site_id
WHERE NOT EXISTS (
    SELECT 1
    FROM tracking.observation_requested_subzones ors
    WHERE ors.observation_id = o.id
);
