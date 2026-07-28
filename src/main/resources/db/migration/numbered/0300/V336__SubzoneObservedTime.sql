ALTER TABLE tracking.planting_subzones ADD COLUMN observed_time TIMESTAMP WITH TIME ZONE;

UPDATE tracking.planting_subzones ps
SET observed_time = (
    SELECT MAX(op.completed_time)
    FROM tracking.observation_plots op
    JOIN tracking.monitoring_plots mp ON op.monitoring_plot_id = mp.id
    WHERE mp.planting_subzone_id = ps.id
);
