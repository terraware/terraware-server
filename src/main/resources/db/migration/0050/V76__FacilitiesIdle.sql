ALTER TABLE facilities ADD COLUMN max_idle_minutes INTEGER NOT NULL DEFAULT 30;
ALTER TABLE facilities ADD COLUMN last_timeseries_time TIMESTAMP WITH TIME ZONE;
ALTER TABLE facilities ADD COLUMN idle_after_time TIMESTAMP WITH TIME ZONE;
ALTER TABLE facilities ADD COLUMN idle_since_time TIMESTAMP WITH TIME ZONE;

-- Don't treat existing facilities as newly idle.
UPDATE facilities
SET idle_since_time = TIMESTAMP WITH TIME ZONE '2022-01-01T00:00:00Z'
WHERE idle_since_time IS NULL;

CREATE INDEX ON facilities (idle_after_time);
