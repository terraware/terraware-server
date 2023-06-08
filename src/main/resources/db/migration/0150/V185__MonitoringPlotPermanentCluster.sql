ALTER TABLE tracking.monitoring_plots ADD COLUMN permanent_cluster INTEGER;
ALTER TABLE tracking.monitoring_plots ADD COLUMN permanent_cluster_subplot INTEGER;

ALTER TABLE tracking.monitoring_plots ADD CONSTRAINT cluster_has_subplot
    CHECK (
        (permanent_cluster IS NOT NULL AND permanent_cluster_subplot IS NOT NULL)
        OR (permanent_cluster IS NULL AND permanent_cluster_subplot IS NULL)
    );

ALTER TABLE tracking.monitoring_plots ADD CONSTRAINT subplot_is_valid
    CHECK (permanent_cluster_subplot BETWEEN 1 AND 4);
