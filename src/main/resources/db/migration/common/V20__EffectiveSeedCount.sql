ALTER TABLE accession ADD COLUMN effective_seed_count INTEGER;
COMMENT ON COLUMN accession.effective_seed_count
    IS 'The exact seed count if available, else the estimated seed count if available, else null.';
