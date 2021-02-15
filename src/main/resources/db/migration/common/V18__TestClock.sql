CREATE TABLE test_clock (
    fake_time TIMESTAMP WITH TIME ZONE NOT NULL,
    real_time TIMESTAMP WITH TIME ZONE NOT NULL
);

COMMENT ON TABLE test_clock
    IS 'User-adjustable clock for test environments. Not used in production.';
COMMENT ON COLUMN test_clock.fake_time
    IS 'What time the server should believe it was at the time the row was written.';
COMMENT ON COLUMN test_clock.real_time
    IS 'What time it was in the real world when the row was written.';
