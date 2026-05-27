CREATE TABLE tracking.planting_site_survival_rate_recalculations
(
    planting_site_id               BIGINT PRIMARY KEY REFERENCES tracking.planting_sites ON DELETE CASCADE,
    last_t0_modified_time          TIMESTAMP WITH TIME ZONE,
    last_observation_modified_time TIMESTAMP WITH TIME ZONE,
    last_recalculated_time         TIMESTAMP WITH TIME ZONE
);
