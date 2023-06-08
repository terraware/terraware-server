CREATE TABLE tracking.observation_states (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

-- These are also in R__TypeCodes.sql but are included here to document the check constraint.
INSERT INTO tracking.observation_states (id, name)
VALUES (1, 'Upcoming'),
       (2, 'InProgress'),
       (3, 'Completed'),
       (4, 'Overdue')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

ALTER TABLE tracking.observations
    ADD COLUMN state_id INTEGER NOT NULL REFERENCES tracking.observation_states;

ALTER TABLE tracking.observations
    ADD CONSTRAINT completed_time_and_state
        CHECK ((completed_time IS NULL AND state_id <> 3) OR
               (completed_time IS NOT NULL AND state_id = 3));

ALTER TABLE tracking.observations
    ADD CONSTRAINT end_after_start
        CHECK (start_date <= end_date);
