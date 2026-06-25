CREATE TABLE tracking.dependent_substratum_observation (
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    substratum_history_id BIGINT NOT NULL REFERENCES tracking.substratum_histories ON DELETE CASCADE,
    depends_on_observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    depends_on_substratum_history_id BIGINT NOT NULL REFERENCES tracking.substratum_histories ON DELETE CASCADE,

    PRIMARY KEY (observation_id, substratum_history_id)
);

CREATE INDEX ON tracking.dependent_substratum_observation (depends_on_observation_id, depends_on_substratum_history_id);

INSERT INTO tracking.dependent_substratum_observation
    (observation_id, substratum_history_id, depends_on_observation_id, depends_on_substratum_history_id)
SELECT consuming.id,
       consuming_ssh.id,
       src.depends_on_observation_id,
       src_ssh.id
FROM tracking.observations consuming
JOIN tracking.stratum_histories consuming_sh
    ON consuming_sh.planting_site_history_id = consuming.planting_site_history_id
JOIN tracking.substratum_histories consuming_ssh
    ON consuming_ssh.stratum_history_id = consuming_sh.id
JOIN LATERAL (
    SELECT o2.id AS depends_on_observation_id
    FROM tracking.observations o2
    WHERE o2.is_ad_hoc = FALSE
      AND (o2.id = consuming.id OR o2.completed_time <= consuming.completed_time)
      -- The substratum must have been actually observed (a completed plot), not merely requested.
      AND EXISTS (
          SELECT 1
          FROM tracking.observation_plots op
          JOIN tracking.monitoring_plot_histories mph
              ON mph.id = op.monitoring_plot_history_id
          WHERE op.observation_id = o2.id
            AND op.completed_time IS NOT NULL
            AND mph.substratum_id = consuming_ssh.substratum_id
      )
    ORDER BY (o2.id = consuming.id) DESC, o2.completed_time DESC
    LIMIT 1
) src ON TRUE
JOIN tracking.observations src_obs
    ON src_obs.id = src.depends_on_observation_id
JOIN tracking.stratum_histories src_sh
    ON src_sh.planting_site_history_id = src_obs.planting_site_history_id
JOIN tracking.substratum_histories src_ssh
    ON src_ssh.stratum_history_id = src_sh.id
   AND src_ssh.substratum_id = consuming_ssh.substratum_id
WHERE consuming.is_ad_hoc = FALSE
  AND consuming.completed_time IS NOT NULL;
