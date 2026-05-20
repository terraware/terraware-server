CREATE TABLE funder.published_activity_observation_media_files (
    file_id BIGINT PRIMARY KEY REFERENCES files,
    activity_id BIGINT NOT NULL REFERENCES funder.published_activities,
    monitoring_plot_id BIGINT NOT NULL REFERENCES tracking.monitoring_plots,
    position_id INTEGER REFERENCES tracking.observation_plot_positions,
    type_id INTEGER NOT NULL REFERENCES tracking.observation_media_types
);

CREATE INDEX ON funder.published_activity_observation_media_files (activity_id);
CREATE INDEX ON funder.published_activity_observation_media_files (monitoring_plot_id);
