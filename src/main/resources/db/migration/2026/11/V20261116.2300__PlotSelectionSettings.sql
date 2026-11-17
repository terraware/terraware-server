CREATE TABLE tracking.plot_selection_modes (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

INSERT INTO tracking.plot_selection_modes (id, name)
VALUES (1, 'Precision Level'), (2, 'Custom'), (3, 'Direct');

ALTER TABLE tracking.stratum_plot_selection_parameters
    ADD COLUMN plot_precision_level_id INTEGER REFERENCES tracking.plot_precision_levels,
    ADD COLUMN plot_selection_mode_id INTEGER NOT NULL DEFAULT 2
        REFERENCES tracking.plot_selection_modes,
    ADD CHECK ((plot_selection_mode_id = 1) = (plot_precision_level_id IS NOT NULL));

CREATE INDEX ON tracking.stratum_plot_selection_parameters (plot_precision_level_id);
CREATE INDEX ON tracking.stratum_plot_selection_parameters (plot_selection_mode_id);
