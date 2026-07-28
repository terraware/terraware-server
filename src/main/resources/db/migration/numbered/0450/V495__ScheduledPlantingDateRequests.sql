CREATE TABLE tracking.planting_date_request_statuses (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE tracking.planting_date_requests (
    scheduled_planting_date_id BIGINT PRIMARY KEY REFERENCES tracking.scheduled_planting_dates ON DELETE CASCADE,
    date DATE NOT NULL,
    created_by BIGINT NOT NULL REFERENCES users,
    created_time TIMESTAMP WITH TIME ZONE NOT NULL,
    modified_by BIGINT NOT NULL REFERENCES users,
    modified_time TIMESTAMP WITH TIME ZONE NOT NULL,
    notes TEXT,
    status_id INTEGER NOT NULL REFERENCES tracking.planting_date_request_statuses
);

CREATE TABLE tracking.planting_date_request_species (
    scheduled_planting_date_id BIGINT NOT NULL REFERENCES tracking.planting_date_requests (scheduled_planting_date_id) ON DELETE CASCADE,
    substratum_id BIGINT NOT NULL REFERENCES tracking.substrata ON DELETE CASCADE,
    species_id BIGINT NOT NULL REFERENCES species ON DELETE CASCADE,
    quantity INTEGER NOT NULL CHECK (quantity >= 0),

    PRIMARY KEY (scheduled_planting_date_id, substratum_id, species_id)
);

CREATE INDEX ON tracking.planting_date_request_species (substratum_id);
CREATE INDEX ON tracking.planting_date_request_species (species_id);

ALTER TABLE nursery.withdrawals
    ADD COLUMN scheduled_planting_date_request_id BIGINT REFERENCES tracking.planting_date_requests (scheduled_planting_date_id) ON DELETE SET NULL;

ALTER TABLE nursery.withdrawals
    ADD CONSTRAINT withdrawals_planting_date_request_season
        CHECK (scheduled_planting_date_request_id IS NULL OR planting_season_id IS NOT NULL);
