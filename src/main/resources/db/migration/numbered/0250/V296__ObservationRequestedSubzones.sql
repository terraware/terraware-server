CREATE TABLE tracking.observation_requested_subzones (
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    planting_subzone_id BIGINT NOT NULL REFERENCES tracking.planting_subzones ON DELETE CASCADE,

    PRIMARY KEY (observation_id, planting_subzone_id)
);

CREATE INDEX ON tracking.observation_requested_subzones (planting_subzone_id);
