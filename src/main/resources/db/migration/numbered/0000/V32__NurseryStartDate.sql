ALTER TABLE accession ADD COLUMN nursery_start_date DATE;
COMMENT ON COLUMN accession.nursery_start_date
    IS 'When the accession was moved to a nursery, or null if it is not in a nursery.';
