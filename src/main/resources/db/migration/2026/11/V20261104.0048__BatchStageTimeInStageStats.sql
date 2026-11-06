ALTER TABLE nursery.batch_stage_details
    ADD COLUMN counted_quantity INTEGER,
    ADD COLUMN counted_seedling_days DOUBLE PRECISION,
    ADD COLUMN open_seedling_days DOUBLE PRECISION,
    ADD COLUMN time_in_stage_cleared BOOLEAN,
    ADD COLUMN time_in_stage_as_of TIMESTAMP WITH TIME ZONE;
