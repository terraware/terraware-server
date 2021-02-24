CREATE TABLE task_processed_time (
    name TEXT PRIMARY KEY,
    processed_up_to TIMESTAMP WITH TIME ZONE NOT NULL
);

COMMENT ON TABLE task_processed_time
    IS 'Tracks the most recently processed time for recurring tasks that need to cover non-overlapping time periods.';
