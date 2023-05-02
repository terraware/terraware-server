-- Convert monitoring plots from MultiPolygon to Polygon.

ALTER TABLE tracking.monitoring_plots ADD COLUMN polygon_boundary GEOMETRY(Polygon);

UPDATE tracking.monitoring_plots
SET polygon_boundary = ST_GeometryN(boundary, 1)
WHERE polygon_boundary IS NULL;

ALTER TABLE tracking.monitoring_plots ALTER COLUMN polygon_boundary SET NOT NULL;

ALTER TABLE tracking.monitoring_plots DROP COLUMN boundary;
ALTER TABLE tracking.monitoring_plots RENAME COLUMN polygon_boundary TO boundary;
