ALTER TABLE nursery.batches ADD COLUMN initial_batch_id BIGINT REFERENCES nursery.batches ON DELETE SET NULL;
CREATE INDEX ON nursery.batches (initial_batch_id);

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
       substrate_id,
       substrate_notes,
       treatment_id,
       treatment_notes,
       germination_rate,
       loss_rate,
       initial_batch_id
FROM nursery.batches b;
