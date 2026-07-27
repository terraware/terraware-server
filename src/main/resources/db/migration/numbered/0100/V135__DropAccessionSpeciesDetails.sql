ALTER TABLE accessions DROP COLUMN family_name;
ALTER TABLE accessions DROP COLUMN rare_type_id;
ALTER TABLE accessions DROP COLUMN species_endangered_type_id;

DROP TABLE species_endangered_types;
DROP TABLE rare_types;
