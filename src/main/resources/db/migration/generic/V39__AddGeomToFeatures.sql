-- When running on PostgreSQL, we use the postGIS geometry type. Add the geom column
-- as text for other databases. Note that no database other than PostgreSQL is officially supported
-- by the project.

ALTER TABLE features
    ADD COLUMN geom TEXT;
