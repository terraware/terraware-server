DROP VIEW tracking.planting_subzone_populations;

CREATE TABLE tracking.planting_subzone_populations (
    planting_subzone_id BIGINT NOT NULL REFERENCES tracking.planting_subzones ON DELETE CASCADE,
    species_id BIGINT NOT NULL REFERENCES species ON DELETE CASCADE,
    total_plants INTEGER NOT NULL,
    plants_since_last_observation INTEGER,

    PRIMARY KEY (planting_subzone_id, species_id)
);

CREATE TABLE tracking.planting_zone_populations (
    planting_zone_id BIGINT NOT NULL REFERENCES tracking.planting_zones ON DELETE CASCADE,
    species_id BIGINT NOT NULL REFERENCES species ON DELETE CASCADE,
    total_plants INTEGER NOT NULL,
    plants_since_last_observation INTEGER,

    PRIMARY KEY (planting_zone_id, species_id)
);

DROP VIEW tracking.planting_site_populations;

CREATE TABLE tracking.planting_site_populations (
    planting_site_id BIGINT NOT NULL REFERENCES tracking.planting_sites ON DELETE CASCADE,
    species_id BIGINT NOT NULL REFERENCES species ON DELETE CASCADE,
    total_plants INTEGER NOT NULL,
    plants_since_last_observation INTEGER,

    PRIMARY KEY (planting_site_id, species_id)
);
