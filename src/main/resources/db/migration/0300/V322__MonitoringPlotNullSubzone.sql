ALTER TABLE tracking.monitoring_plots ALTER COLUMN planting_subzone_id DROP NOT NULL;

ALTER TABLE tracking.monitoring_plots DROP CONSTRAINT monitoring_plots_planting_subzone_id_fkey;
ALTER TABLE tracking.monitoring_plots ADD
    FOREIGN KEY (planting_subzone_id)
        REFERENCES tracking.planting_subzones
        ON DELETE SET NULL;
