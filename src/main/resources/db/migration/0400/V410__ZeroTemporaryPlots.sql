ALTER TABLE tracking.planting_zones DROP CONSTRAINT must_have_temporary_plots;
ALTER TABLE tracking.planting_zones ADD CONSTRAINT no_negative_temporary_plots
    CHECK (num_temporary_plots >= 0);
