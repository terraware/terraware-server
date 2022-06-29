-- Add a couple of server-calculated fields for seed viability. These are updated as the underlying
-- source data changes. These need to be efficiently queryable, so we can't just compute them in a
-- view (it'd have to be a materialized view which would then need to be refreshed).
ALTER TABLE accession ADD COLUMN latest_germination_recording_date DATE;
ALTER TABLE accession ADD COLUMN latest_viability_percent INTEGER;
ALTER TABLE accession ADD COLUMN total_viability_percent INTEGER;

COMMENT ON COLUMN accession.latest_viability_percent
    IS 'Percent of seeds germinated in most recent viability test, or in cut test if no germinations exist yet';
COMMENT ON COLUMN accession.total_viability_percent
    IS 'Percentage of viable seeds across all tests';
