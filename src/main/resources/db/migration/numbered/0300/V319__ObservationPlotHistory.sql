-- Record which version of a plot was observed so we can link it to its correct subzone at the
-- time of the observation.
ALTER TABLE tracking.observation_plots
    ADD COLUMN monitoring_plot_history_id BIGINT
        REFERENCES tracking.monitoring_plot_histories ON DELETE CASCADE;
CREATE INDEX ON tracking.observation_plots (monitoring_plot_history_id);

UPDATE tracking.observation_plots op
SET monitoring_plot_history_id = (
    SELECT id
    FROM tracking.monitoring_plot_histories mph
    WHERE mph.monitoring_plot_id = op.monitoring_plot_id
);

ALTER TABLE tracking.observation_plots
    ALTER COLUMN monitoring_plot_history_id SET NOT NULL;
