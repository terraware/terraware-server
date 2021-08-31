-- The shape type of a map feature is included in its geometry data, so there's no need to also
-- have it in a separate column.
ALTER TABLE features DROP COLUMN shape_type_id;

DROP TABLE shape_types;
