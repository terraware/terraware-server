ALTER TABLE tracking.monitoring_plots
    ADD COLUMN planting_site_id BIGINT
        REFERENCES tracking.planting_sites ON DELETE CASCADE;

UPDATE tracking.monitoring_plots mp
SET planting_site_id = (
    SELECT planting_site_id
    FROM tracking.planting_subzones ps
    WHERE ps.id = mp.planting_subzone_id
);

ALTER TABLE tracking.monitoring_plots
    ALTER COLUMN planting_site_id SET NOT NULL;
