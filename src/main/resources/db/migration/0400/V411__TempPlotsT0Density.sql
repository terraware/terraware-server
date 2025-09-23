ALTER TABLE tracking.planting_sites
    ADD COLUMN survival_rate_includes_temp_plots BOOLEAN;

CREATE TABLE tracking.zone_t0_temp_density
(
    planting_zone_id BIGINT                   NOT NULL REFERENCES tracking.planting_zones ON DELETE CASCADE,
    species_id       BIGINT                   NOT NULL REFERENCES species ON DELETE CASCADE,
    zone_density     NUMERIC                  NOT NULL,
    created_time     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    modified_time    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by       BIGINT                   NOT NULL REFERENCES users,
    modified_by      BIGINT                   NOT NULL REFERENCES users,
    PRIMARY KEY (planting_zone_id, species_id)
);
