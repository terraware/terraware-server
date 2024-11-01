-- The number of temporary plots is based on the number of permanent plots, which used to be
-- 4 times the number of permanent clusters but is now equal to the number of permanent clusters.
-- Update any planting zones whose temporary plot count followed that formula, but don't touch
-- ones whose plot counts were manually adjusted to some other value.

UPDATE tracking.planting_zones
SET num_temporary_plots = greatest(1, floor(num_permanent_clusters / 3))
WHERE num_temporary_plots = floor(num_permanent_clusters * 4 / 3);
