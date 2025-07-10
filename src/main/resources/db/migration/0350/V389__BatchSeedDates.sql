ALTER TABLE nursery.batches ADD COLUMN germination_started_date DATE;
ALTER TABLE nursery.batches ADD COLUMN seeds_sown_date DATE;

ALTER TABLE nursery.batch_details_history ADD COLUMN germination_started_date DATE;
ALTER TABLE nursery.batch_details_history ADD COLUMN seeds_sown_date DATE;
