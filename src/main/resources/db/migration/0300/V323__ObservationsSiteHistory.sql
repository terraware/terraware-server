ALTER TABLE tracking.observations
    ADD COLUMN planting_site_history_id BIGINT
        REFERENCES tracking.planting_site_histories ON DELETE CASCADE;

CREATE INDEX ON tracking.observations (planting_site_history_id);

UPDATE tracking.observations o
SET planting_site_history_id = (
    SELECT MAX(id)
    FROM tracking.planting_site_histories psh
    WHERE o.planting_site_id = psh.planting_site_id
)
WHERE o.state_id <> 1;

ALTER TABLE tracking.observations
    ADD CONSTRAINT history_id_required_at_start
        CHECK (
            state_id = 1 AND observations.planting_site_history_id IS NULL
            OR state_id <> 1 AND observations.planting_site_history_id IS NOT NULL
        );
