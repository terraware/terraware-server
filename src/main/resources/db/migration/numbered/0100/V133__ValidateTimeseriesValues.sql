-- The previous migration created the constraint on timeseries_values with the NOT VALID option to
-- avoid holding an exclusive lock on it for an extended period of time. Validate the constraint
-- in this separate migration, which will run in a new transaction and thus won't hold any locks
-- acquired in the previous one. (Validating an existing constraint does not require an exclusive
-- table lock.)

ALTER TABLE timeseries_values VALIDATE CONSTRAINT timeseries_values_timeseries_id_fkey;
