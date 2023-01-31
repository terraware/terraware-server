ALTER TABLE seedbank.accessions ADD COLUMN total_withdrawn_count INTEGER;
ALTER TABLE seedbank.accessions ADD COLUMN total_withdrawn_weight_grams NUMERIC;
ALTER TABLE seedbank.accessions ADD COLUMN total_withdrawn_weight_quantity NUMERIC;
ALTER TABLE seedbank.accessions ADD COLUMN total_withdrawn_weight_units_id INTEGER REFERENCES seedbank.seed_quantity_units;
