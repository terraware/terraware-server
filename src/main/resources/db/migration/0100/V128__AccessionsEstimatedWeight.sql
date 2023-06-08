ALTER TABLE accessions ADD COLUMN est_weight_grams NUMERIC;
ALTER TABLE accessions ADD COLUMN est_weight_quantity NUMERIC;
ALTER TABLE accessions ADD COLUMN est_weight_units_id INTEGER REFERENCES seed_quantity_units;
