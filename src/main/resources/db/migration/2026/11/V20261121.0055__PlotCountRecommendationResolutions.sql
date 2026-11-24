CREATE TABLE tracking.plot_count_recommendation_resolutions (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

ALTER TABLE tracking.observation_stratum_variability
    ADD COLUMN recommendation_resolution_id INTEGER REFERENCES tracking.plot_count_recommendation_resolutions,
    ADD COLUMN resolved_by BIGINT REFERENCES users,
    ADD COLUMN resolved_time TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT recommendation_resolution_complete CHECK (
        (recommendation_resolution_id IS NULL AND resolved_by IS NULL AND resolved_time IS NULL)
        OR (recommendation_resolution_id IS NOT NULL AND resolved_by IS NOT NULL AND resolved_time IS NOT NULL)
    );

CREATE INDEX ON tracking.observation_stratum_variability (resolved_by);
