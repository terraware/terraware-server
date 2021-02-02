-- Allow a germination test to be created with no start date.
ALTER TABLE germination_test ALTER COLUMN start_date DROP NOT NULL;
