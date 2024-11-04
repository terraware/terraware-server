CREATE TABLE tracking.observation_plot_statuses (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

-- This is also in R__TypeCodes.sql, but is repeated here to ensure migration is successful
INSERT INTO tracking.observation_plot_statuses (id, name)
VALUES (1, 'Unclaimed'),
       (2, 'Claimed'),
       (3, 'Completed'),
       (4, 'Not Observed')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

ALTER TABLE tracking.observation_plots ADD COLUMN status_id INTEGER REFERENCES tracking.observation_plot_statuses;

UPDATE tracking.observation_plots
SET status_id = CASE
    WHEN completed_time IS NOT NULL THEN 3
    WHEN claimed_time IS NOT NULL THEN 2
    ELSE 1
END;

ALTER TABLE tracking.observation_plots ALTER COLUMN status_id SET NOT NULL;
