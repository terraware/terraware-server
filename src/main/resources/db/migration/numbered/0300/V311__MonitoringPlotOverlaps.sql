CREATE TABLE tracking.monitoring_plot_overlaps (
    monitoring_plot_id BIGINT NOT NULL REFERENCES tracking.monitoring_plots ON DELETE CASCADE,
    overlaps_plot_id BIGINT NOT NULL REFERENCES tracking.monitoring_plots ON DELETE CASCADE,

    PRIMARY KEY (monitoring_plot_id, overlaps_plot_id),

    CONSTRAINT newer_overlaps_older CHECK (overlaps_plot_id < monitoring_plot_id)
);

CREATE INDEX ON tracking.monitoring_plot_overlaps (overlaps_plot_id);
