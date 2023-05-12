UPDATE tracking.planting_zones SET error_margin = 40 WHERE error_margin IS NULL;
ALTER TABLE tracking.planting_zones ALTER COLUMN error_margin SET NOT NULL;

UPDATE tracking.planting_zones SET variance = 6700 WHERE variance IS NULL;
ALTER TABLE tracking.planting_zones ALTER COLUMN variance SET NOT NULL;

UPDATE tracking.planting_zones SET students_t = 1.645 WHERE students_t IS NULL;
ALTER TABLE tracking.planting_zones ALTER COLUMN students_t SET NOT NULL;

UPDATE tracking.planting_zones SET num_permanent_clusters = 12 WHERE num_permanent_clusters IS NULL;
ALTER TABLE tracking.planting_zones ALTER COLUMN num_permanent_clusters SET NOT NULL;
ALTER TABLE tracking.planting_zones ADD CONSTRAINT must_have_permanent_clusters
    CHECK (num_permanent_clusters > 0);

UPDATE tracking.planting_zones SET num_temporary_plots = 16 WHERE num_temporary_plots IS NULL;
ALTER TABLE tracking.planting_zones ALTER COLUMN num_temporary_plots SET NOT NULL;
ALTER TABLE tracking.planting_zones ADD CONSTRAINT must_have_temporary_plots
    CHECK (num_temporary_plots > 0);
