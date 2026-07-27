-- First, get rid of all the plots that have never been in an observation.
DELETE FROM tracking.monitoring_plots mp
WHERE NOT EXISTS (
    SELECT 1
    FROM tracking.observation_plots op
    WHERE op.monitoring_plot_id = mp.id
);

-- Now we have two classes of plots: permanent clusters that have been in an observation, and
-- temporary plots that have been in an observation. The temporary plots will often have been
-- members of permanent clusters (since we used to create all possible clusters during shapefile
-- import) which are no longer valid because they no longer have 4 plots thanks to the DELETE
-- statement above. Strip the permanent cluster numbers from plots whose clusters no longer have
-- a full set of 4 plots.
UPDATE tracking.monitoring_plots
SET permanent_cluster = NULL,
    permanent_cluster_subplot = NULL
WHERE id IN (
    WITH plots_with_zones AS (
            SELECT mp.id, mp.permanent_cluster, psz.planting_zone_id
            FROM tracking.monitoring_plots mp
            JOIN tracking.planting_subzones psz
                ON mp.planting_subzone_id = psz.id
            WHERE mp.permanent_cluster IS NOT NULL
        ),
        invalid_clusters AS (
            SELECT planting_zone_id, permanent_cluster
            FROM plots_with_zones
            GROUP BY planting_zone_id, permanent_cluster
            HAVING COUNT(*) < 4
        )
    SELECT plots_with_zones.id
    FROM plots_with_zones
    JOIN invalid_clusters
        ON plots_with_zones.planting_zone_id = invalid_clusters.planting_zone_id
        AND plots_with_zones.permanent_cluster = invalid_clusters.permanent_cluster
);
