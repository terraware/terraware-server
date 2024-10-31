ALTER TABLE tracking.observations DROP CONSTRAINT completed_time_and_state;

-- From V191. Added constraints for abandon state to require completion time
ALTER TABLE tracking.observations
    ADD CONSTRAINT completed_time_and_state
        CHECK ((completed_time IS NULL AND state_id <> 3) OR
               (completed_time IS NULL AND state_id <> 5) OR
               (completed_time IS NOT NULL AND state_id = 3) OR
               (completed_time IS NOT NULL AND state_id = 5));
