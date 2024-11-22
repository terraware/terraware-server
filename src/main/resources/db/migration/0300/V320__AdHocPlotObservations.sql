CREATE TABLE tracking.observation_types (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

ALTER TABLE tracking.observations ADD COLUMN is_ad_hoc BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE tracking.observations ADD COLUMN observation_type_id INTEGER REFERENCES tracking.observation_types;
ALTER TABLE tracking.observations ADD CONSTRAINT ad_hoc_observation_type CHECK (
    (is_ad_hoc = FALSE AND observation_type_id IS NULL)
    OR (is_ad_hoc = TRUE AND observation_type_id IS NOT NULL)
);
