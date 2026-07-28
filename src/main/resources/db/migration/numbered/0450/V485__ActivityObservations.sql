CREATE TABLE accelerator.activity_observations (
    activity_id BIGINT PRIMARY KEY REFERENCES accelerator.activities ON DELETE CASCADE,
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,

    UNIQUE (observation_id)
);

CREATE TABLE funder.published_activity_observations (
    activity_id BIGINT PRIMARY KEY REFERENCES funder.published_activities ON DELETE CASCADE,
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    live_plants INTEGER,
    plant_density INTEGER,
    survival_rate INTEGER,

    UNIQUE (observation_id)
);

-- Activity photos/videos for observations don't need any observation-specific metadata (just
-- logic to generate default captions and order them correctly). We can insert references to them
-- into activity_media_files.
