CREATE TABLE tracking.planting_season_allocated_species (
    planting_season_id BIGINT NOT NULL REFERENCES tracking.planting_seasons ON DELETE CASCADE,
    species_id BIGINT NOT NULL REFERENCES species ON DELETE CASCADE,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    created_by BIGINT NOT NULL REFERENCES users,
    created_time TIMESTAMP WITH TIME ZONE NOT NULL,
    modified_by BIGINT NOT NULL REFERENCES users,
    modified_time TIMESTAMP WITH TIME ZONE NOT NULL,

    PRIMARY KEY (planting_season_id, species_id)
);

CREATE INDEX ON tracking.planting_season_allocated_species (species_id);
