-- Change IDs on reference tables to INTEGER for consistency.

ALTER TABLE germination_treatment ALTER COLUMN id TYPE INTEGER;
ALTER TABLE germination_test ALTER COLUMN treatment_id TYPE INTEGER;

ALTER TABLE germination_substrate ALTER COLUMN id TYPE INTEGER;
ALTER TABLE germination_test ALTER COLUMN substrate_id TYPE INTEGER;

ALTER TABLE germination_seed_type ALTER COLUMN id TYPE INTEGER;
ALTER TABLE germination_test ALTER COLUMN seed_type_id TYPE INTEGER;

ALTER TABLE withdrawal_purpose ALTER COLUMN id TYPE INTEGER;
ALTER TABLE withdrawal ALTER COLUMN purpose_id TYPE INTEGER;
