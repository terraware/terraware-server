CREATE TABLE tracking.plot_precision_levels (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    target_margin_of_error NUMERIC NOT NULL
);

INSERT INTO tracking.plot_precision_levels (id, name, target_margin_of_error)
VALUES (1, 'Very Low', 22.5), (2, 'Low', 20), (3, 'Medium', 15), (4, 'High', 10), (5, 'Very High', 7);

ALTER TABLE tracking.planting_sites
    ADD COLUMN default_plot_precision_level_id INTEGER NOT NULL DEFAULT 3
        REFERENCES tracking.plot_precision_levels;

CREATE INDEX ON tracking.planting_sites (default_plot_precision_level_id);
