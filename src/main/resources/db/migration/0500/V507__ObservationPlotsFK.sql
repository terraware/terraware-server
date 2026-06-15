ALTER TABLE tracking.observation_plot_conditions
    ADD FOREIGN KEY (observation_id, monitoring_plot_id)
        REFERENCES tracking.observation_plots (observation_id, monitoring_plot_id)
        ON DELETE CASCADE;

-- Existing observed_plot_coordinates FK doesn't have ON DELETE CASCADE
ALTER TABLE tracking.observed_plot_coordinates
    ADD FOREIGN KEY (observation_id, monitoring_plot_id)
        REFERENCES tracking.observation_plots (observation_id, monitoring_plot_id)
        ON DELETE CASCADE;

ALTER TABLE tracking.observed_plot_coordinates
    DROP CONSTRAINT observed_plot_coordinates_observation_id_monitoring_plot_i_fkey;
