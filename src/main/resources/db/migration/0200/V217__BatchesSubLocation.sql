ALTER TABLE nursery.batches ADD COLUMN sub_location_id BIGINT REFERENCES sub_locations ON DELETE SET NULL;

ALTER TABLE sub_locations ADD UNIQUE (facility_id, id);

ALTER TABLE nursery.batches ADD CONSTRAINT sub_location_matches_facility
    FOREIGN KEY (facility_id, sub_location_id) REFERENCES sub_locations (facility_id, id);

CREATE OR REPLACE VIEW nursery.batch_summaries AS
SELECT id,
       organization_id,
       facility_id,
       species_id,
       batch_number,
       added_date,
       ready_quantity,
       not_ready_quantity,
       germinating_quantity,
       ready_quantity + not_ready_quantity AS total_quantity,
       ready_by_date,
       notes,
       accession_id,
       COALESCE(
               (SELECT SUM(bw.ready_quantity_withdrawn + bw.not_ready_quantity_withdrawn)
                FROM nursery.batch_withdrawals bw
                WHERE b.id = bw.batch_id),
               0) AS total_quantity_withdrawn,
       version,
       project_id,
       sub_location_id
FROM nursery.batches b;
