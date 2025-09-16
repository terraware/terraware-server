ALTER TABLE tracking.plot_t0_density
    ADD COLUMN created_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ADD COLUMN modified_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ADD COLUMN created_by BIGINT REFERENCES users,
    ADD COLUMN modified_by BIGINT REFERENCES users;

ALTER TABLE tracking.plot_t0_observations
    ADD COLUMN created_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ADD COLUMN modified_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ADD COLUMN created_by BIGINT REFERENCES users,
    ADD COLUMN modified_by BIGINT REFERENCES users;


-- set existing to system user (should only be in staging)
UPDATE tracking.plot_t0_density
SET (created_by, modified_by) = (
    SELECT u.id, u.id
    FROM users u
    WHERE u.user_type_id = 4
);
UPDATE tracking.plot_t0_observations
SET (created_by, modified_by) = (
    SELECT u.id, u.id
    FROM users u
    WHERE u.user_type_id = 4
);

ALTER TABLE tracking.plot_t0_density
    ALTER COLUMN created_by SET NOT NULL,
    ALTER COLUMN modified_by SET NOT NULL;

ALTER TABLE tracking.plot_t0_observations
    ALTER COLUMN created_by SET NOT NULL,
    ALTER COLUMN modified_by SET NOT NULL;
