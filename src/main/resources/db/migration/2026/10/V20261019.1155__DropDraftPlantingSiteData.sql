ALTER TABLE tracking.draft_planting_sites ALTER COLUMN site_type_id SET NOT NULL;

ALTER TABLE tracking.draft_planting_sites DROP COLUMN data;
ALTER TABLE tracking.draft_planting_sites DROP COLUMN num_strata;
ALTER TABLE tracking.draft_planting_sites DROP COLUMN num_substrata;
