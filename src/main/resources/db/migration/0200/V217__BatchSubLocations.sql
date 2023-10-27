ALTER TABLE nursery.batches ADD UNIQUE (facility_id, id);
ALTER TABLE sub_locations ADD UNIQUE (facility_id, id);

CREATE TABLE nursery.batch_sub_locations (
    batch_id BIGINT NOT NULL REFERENCES nursery.batches ON DELETE CASCADE,
    sub_location_id BIGINT NOT NULL REFERENCES sub_locations ON DELETE CASCADE,
    facility_id BIGINT NOT NULL REFERENCES facilities ON DELETE CASCADE,

    PRIMARY KEY (batch_id, sub_location_id),

    FOREIGN KEY (facility_id, sub_location_id) REFERENCES sub_locations (facility_id, id),
    FOREIGN KEY (facility_id, batch_id) REFERENCES nursery.batches (facility_id, id)
);

CREATE INDEX ON nursery.batch_sub_locations (sub_location_id);
