ALTER TABLE species ADD COLUMN common_name_dataset_type_id INTEGER REFERENCES external_dataset_types;
ALTER TABLE species ADD COLUMN common_name_dataset_date DATE;
ALTER TABLE species ADD CONSTRAINT common_name_source_has_both_values
    CHECK ((common_name_dataset_type_id IS NULL) = (common_name_dataset_date IS NULL));
ALTER TABLE species ADD CONSTRAINT common_name_source_has_name
    CHECK (common_name_dataset_type_id IS NULL OR common_name IS NOT NULL);

ALTER TABLE species ADD COLUMN family_name_dataset_type_id INTEGER REFERENCES external_dataset_types;
ALTER TABLE species ADD COLUMN family_name_dataset_date DATE;
ALTER TABLE species ADD CONSTRAINT family_name_source_has_both_values
    CHECK ((family_name_dataset_type_id IS NULL) = (family_name_dataset_date IS NULL));
ALTER TABLE species ADD CONSTRAINT family_name_source_requires_name
    CHECK (family_name_dataset_type_id IS NULL OR family_name IS NOT NULL);
