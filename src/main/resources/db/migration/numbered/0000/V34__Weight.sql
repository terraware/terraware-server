CREATE TABLE seed_quantity_units (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL
);

ALTER TABLE accession ADD COLUMN remaining_grams NUMERIC;
ALTER TABLE accession ADD COLUMN remaining_quantity NUMERIC;
ALTER TABLE accession ADD COLUMN remaining_units_id INTEGER REFERENCES seed_quantity_units (id);
ALTER TABLE accession ADD COLUMN subset_weight_grams NUMERIC;
ALTER TABLE accession ADD COLUMN subset_weight_quantity NUMERIC;
ALTER TABLE accession ADD COLUMN subset_weight_units_id INTEGER REFERENCES seed_quantity_units (id);
ALTER TABLE accession ADD COLUMN total_grams NUMERIC;
ALTER TABLE accession ADD COLUMN total_quantity NUMERIC;
ALTER TABLE accession ADD COLUMN total_units_id INTEGER REFERENCES seed_quantity_units (id);

ALTER TABLE germination_test ADD COLUMN remaining_grams NUMERIC;
ALTER TABLE germination_test ADD COLUMN remaining_quantity NUMERIC NOT NULL;
ALTER TABLE germination_test ADD COLUMN remaining_units_id INTEGER NOT NULL REFERENCES seed_quantity_units (id);

ALTER TABLE withdrawal ADD COLUMN remaining_grams NUMERIC;
ALTER TABLE withdrawal ADD COLUMN remaining_quantity NUMERIC NOT NULL;
ALTER TABLE withdrawal ADD COLUMN remaining_units_id INTEGER NOT NULL REFERENCES seed_quantity_units (id);
ALTER TABLE withdrawal ADD COLUMN withdrawn_grams NUMERIC;
ALTER TABLE withdrawal ADD COLUMN withdrawn_quantity NUMERIC;
ALTER TABLE withdrawal ADD COLUMN withdrawn_units_id INTEGER REFERENCES seed_quantity_units (id);

ALTER TABLE accession DROP COLUMN effective_seed_count;
ALTER TABLE accession DROP COLUMN seeds_remaining;
ALTER TABLE accession DROP COLUMN seeds_counted;
ALTER TABLE accession DROP COLUMN total_weight;

ALTER TABLE withdrawal DROP COLUMN grams_withdrawn;
ALTER TABLE withdrawal DROP COLUMN seeds_withdrawn;

ALTER TABLE accession ADD CONSTRAINT subset_weight_units_must_not_be_seeds
    CHECK (subset_weight_units_id <> 1 OR subset_weight_units_id IS NULL);
ALTER TABLE accession ADD CONSTRAINT subset_weight_quantity_must_have_units
    CHECK ((subset_weight_quantity IS NOT NULL AND subset_weight_units_id IS NOT NULL) OR
           (subset_weight_quantity IS NULL AND subset_weight_units_id IS NULL));
ALTER TABLE accession ADD CONSTRAINT remaining_quantity_must_have_units
    CHECK ((remaining_quantity IS NOT NULL AND remaining_units_id IS NOT NULL) OR
           (remaining_quantity IS NULL AND remaining_units_id IS NULL));
ALTER TABLE accession ADD CONSTRAINT total_quantity_must_have_units
    CHECK ((total_quantity IS NOT NULL AND total_units_id IS NOT NULL) OR
           (total_quantity IS NULL AND total_units_id IS NULL));
