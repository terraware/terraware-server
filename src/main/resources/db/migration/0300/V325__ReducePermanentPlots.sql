UPDATE tracking.planting_zones
SET num_permanent_clusters =
    greatest(
        1,
        round((num_permanent_clusters - extra_permanent_clusters) * 0.75) + extra_permanent_clusters);
