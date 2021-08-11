ALTER TABLE layers ADD CONSTRAINT tile_set_name_cannot_be_empty_string
    CHECK (layers.tile_set_name is null OR CHAR_LENGTH(layers.tile_set_name) > 0);

ALTER TABLE features ADD CONSTRAINT attrib_cannot_be_empty_string
    CHECK (features.attrib is null OR CHAR_LENGTH(features.attrib) > 0);

ALTER TABLE features ADD CONSTRAINT notes_cannot_be_empty_string
    CHECK (features.notes is null OR CHAR_LENGTH(features.notes) > 0);

ALTER TABLE plants ADD CONSTRAINT label_cannot_be_empty_string
    CHECK (plants.label is null OR CHAR_LENGTH(plants.label) > 0);

ALTER TABLE plant_observations ADD CONSTRAINT pests_cannot_be_empty_string
    CHECK (plant_observations.pests is null OR CHAR_LENGTH(plant_observations.pests ) > 0);

ALTER TABLE photos ADD CONSTRAINT file_name_cannot_be_empty_string
    CHECK (CHAR_LENGTH(photos.file_name) > 0);

ALTER TABLE photos ADD CONSTRAINT content_type_cannot_be_empty_string
    CHECK (CHAR_LENGTH(photos.content_type) > 0);

ALTER TABLE thumbnail ADD CONSTRAINT file_name_cannot_be_empty_string
    CHECK (CHAR_LENGTH(thumbnail.file_name) > 0);
