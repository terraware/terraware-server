CREATE SCHEMA IF NOT EXISTS accelerator;

ALTER TABLE cohort_phases SET SCHEMA accelerator;
ALTER TABLE cohorts SET SCHEMA accelerator;
ALTER TABLE participants SET SCHEMA accelerator;
