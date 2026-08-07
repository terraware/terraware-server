CREATE TABLE tracking.planting_site_species_targets (
    planting_site_id BIGINT NOT NULL REFERENCES tracking.planting_sites ON DELETE CASCADE,
    species_id BIGINT NOT NULL REFERENCES species ON DELETE CASCADE,
    target_plants BIGINT,

    PRIMARY KEY (planting_site_id, species_id)
);

CREATE INDEX ON tracking.planting_site_species_targets (species_id);

CREATE TABLE tracking.stratum_species_targets (
    planting_site_id BIGINT NOT NULL REFERENCES tracking.planting_sites ON DELETE CASCADE,
    stratum_id BIGINT NOT NULL REFERENCES tracking.strata ON DELETE CASCADE,
    species_id BIGINT NOT NULL REFERENCES species ON DELETE CASCADE,

    PRIMARY KEY (planting_site_id, stratum_id, species_id),

    CONSTRAINT species_must_be_targeted_to_site
        FOREIGN KEY (planting_site_id, species_id)
            REFERENCES tracking.planting_site_species_targets (planting_site_id, species_id)
                ON DELETE CASCADE,
    CONSTRAINT stratum_must_be_in_site
        FOREIGN KEY (planting_site_id, stratum_id)
            REFERENCES tracking.strata (planting_site_id, id)
                ON DELETE CASCADE
);

CREATE INDEX ON tracking.stratum_species_targets (species_id);
CREATE INDEX ON tracking.stratum_species_targets (stratum_id);
