ALTER TABLE tracking.planting_zones
    ADD COLUMN variance NUMERIC,
    ADD COLUMN students_t NUMERIC,
    ADD COLUMN error_margin NUMERIC,
    ADD COLUMN num_permanent_clusters INTEGER,
    ADD COLUMN num_temporary_plots INTEGER;
