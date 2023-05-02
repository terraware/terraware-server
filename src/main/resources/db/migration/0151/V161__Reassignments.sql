-- For a given delivery, each species can have an original planting and optionally a single
-- reassignment. A reassignment is modeled as two plantings (one "reassignment from" and one
-- "reassignment to"). A delivery can thus have at most one planting of each type for a species.

ALTER TABLE tracking.plantings
ADD UNIQUE (delivery_id, species_id, planting_type_id);
