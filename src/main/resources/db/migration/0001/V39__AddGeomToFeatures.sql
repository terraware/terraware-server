CREATE EXTENSION IF NOT EXISTS postgis;

ALTER TABLE features
    ADD COLUMN geom geometry;
