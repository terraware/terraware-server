CREATE TABLE tracking.dependent_substratum_observation (
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    substratum_history_id BIGINT NOT NULL REFERENCES tracking.substratum_histories ON DELETE CASCADE,
    depends_on_observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    depends_on_substratum_history_id BIGINT NOT NULL REFERENCES tracking.substratum_histories ON DELETE CASCADE,

    PRIMARY KEY (observation_id, substratum_history_id)
);

CREATE INDEX ON tracking.dependent_substratum_observation (depends_on_observation_id, depends_on_substratum_history_id);
