ALTER TABLE tracking.strata RENAME COLUMN error_margin TO plant_error_margin;
ALTER TABLE tracking.strata RENAME COLUMN num_permanent_plots TO plant_num_permanent_plots;
ALTER TABLE tracking.strata RENAME COLUMN num_temporary_plots TO plant_num_temporary_plots;
ALTER TABLE tracking.strata RENAME COLUMN students_t TO plant_students_t;
ALTER TABLE tracking.strata RENAME COLUMN variance TO plant_variance;
