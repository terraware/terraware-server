ALTER TABLE tracking.observed_site_species_totals
    ADD COLUMN planting_site_history_id BIGINT REFERENCES tracking.planting_site_histories ON DELETE CASCADE;

CREATE UNIQUE INDEX ON tracking.observed_site_species_totals
    (observation_id, planting_site_history_id, species_id)
    WHERE species_id IS NOT NULL;

CREATE UNIQUE INDEX ON tracking.observed_site_species_totals
    (observation_id, planting_site_history_id, species_name)
    WHERE species_name IS NOT NULL;

CREATE UNIQUE INDEX ON tracking.observed_site_species_totals
    (observation_id, planting_site_history_id)
    WHERE species_id IS NULL AND species_name IS NULL;

ALTER TABLE tracking.observed_stratum_species_totals
    ADD COLUMN stratum_history_id BIGINT REFERENCES tracking.stratum_histories ON DELETE CASCADE,
    ALTER COLUMN stratum_id DROP NOT NULL,
    DROP CONSTRAINT observed_stratum_species_totals_stratum_id_fkey,
    ADD CONSTRAINT observed_stratum_species_totals_stratum_id_fkey
        FOREIGN KEY (stratum_id) REFERENCES tracking.strata ON DELETE SET NULL;

DROP INDEX tracking.observed_stratum_sp_totals_observation_id_stratum_idx;
DROP INDEX tracking.observed_stratum_sp_totals_observation_id_stratum_idx1;
DROP INDEX tracking.observed_stratum_sp_totals_observation_id_stratum_idx2;

CREATE UNIQUE INDEX ON tracking.observed_stratum_species_totals
    (observation_id, stratum_history_id, species_id)
    WHERE species_id IS NOT NULL;

CREATE UNIQUE INDEX ON tracking.observed_stratum_species_totals
    (observation_id, stratum_history_id, species_name)
    WHERE species_name IS NOT NULL;

CREATE UNIQUE INDEX ON tracking.observed_stratum_species_totals
    (observation_id, stratum_history_id)
    WHERE species_id IS NULL AND species_name IS NULL;

ALTER TABLE tracking.observed_substratum_species_totals
    ADD COLUMN substratum_history_id BIGINT REFERENCES tracking.substratum_histories ON DELETE CASCADE,
    ALTER COLUMN substratum_id DROP NOT NULL,
    DROP CONSTRAINT observed_substratum_species_totals_substratum_id_fkey,
    ADD CONSTRAINT observed_substratum_species_totals_substratum_id_fkey
        FOREIGN KEY (substratum_id) REFERENCES tracking.substrata ON DELETE SET NULL;

DROP INDEX tracking.observed_substratum_sp_totals_observation_id_substr_idx;
DROP INDEX tracking.observed_substratum_sp_totals_observation_id_substr_idx1;
DROP INDEX tracking.observed_substratum_sp_totals_observation_id_substr_idx2;

CREATE UNIQUE INDEX ON tracking.observed_substratum_species_totals
    (observation_id, substratum_history_id, species_id)
    WHERE species_id IS NOT NULL;

CREATE UNIQUE INDEX ON tracking.observed_substratum_species_totals
    (observation_id, substratum_history_id, species_name)
    WHERE species_name IS NOT NULL;

CREATE UNIQUE INDEX ON tracking.observed_substratum_species_totals
    (observation_id, substratum_history_id)
    WHERE species_id IS NULL AND species_name IS NULL;

ALTER TABLE tracking.observed_plot_species_totals
    ADD COLUMN monitoring_plot_history_id BIGINT REFERENCES tracking.monitoring_plot_histories ON DELETE CASCADE;

CREATE UNIQUE INDEX ON tracking.observed_plot_species_totals
    (observation_id, monitoring_plot_history_id, species_id)
    WHERE species_id IS NOT NULL;

CREATE UNIQUE INDEX ON tracking.observed_plot_species_totals
    (observation_id, monitoring_plot_history_id, species_name)
    WHERE species_name IS NOT NULL;

CREATE UNIQUE INDEX ON tracking.observed_plot_species_totals
    (observation_id, monitoring_plot_history_id)
    WHERE species_id IS NULL AND species_name IS NULL;


UPDATE tracking.observed_site_species_totals as totals
    SET planting_site_history_id = obs.planting_site_history_id
    FROM tracking.observations as obs
    WHERE obs.id = totals.observation_id;

UPDATE tracking.observed_stratum_species_totals as totals
    SET stratum_history_id = sh.id
    FROM tracking.observations as obs
    JOIN tracking.stratum_histories as sh
        ON sh.planting_site_history_id = obs.planting_site_history_id
    WHERE obs.id = totals.observation_id
        AND sh.stratum_id = totals.stratum_id;

UPDATE tracking.observed_substratum_species_totals as totals
    SET substratum_history_id = ssh.id
    FROM tracking.observations as obs
    JOIN tracking.stratum_histories as sh
        ON sh.planting_site_history_id = obs.planting_site_history_id
    JOIN tracking.substratum_histories as ssh
        ON ssh.stratum_history_id = sh.id
    WHERE obs.id = totals.observation_id
        AND ssh.substratum_id = totals.substratum_id;

-- There are some monitoring plots with missing history for some reason. Identify them and fix.
INSERT INTO tracking.monitoring_plot_histories (
    monitoring_plot_id, substratum_id, planting_site_id, substratum_history_id,
    planting_site_history_id, created_by, created_time
)
SELECT DISTINCT plots.id,
                plots.substratum_id,
                obs.planting_site_id,
                (SELECT shist.id
                 FROM tracking.substratum_histories shist
                     JOIN tracking.stratum_histories strh ON strh.id = shist.stratum_history_id
                 WHERE strh.planting_site_history_id = obs.planting_site_history_id
                     AND shist.substratum_id = plots.substratum_id),
                obs.planting_site_history_id,
                psh.created_by,
                psh.created_time
FROM tracking.observed_plot_species_totals totals
    JOIN tracking.observations obs ON obs.id = totals.observation_id
    JOIN tracking.monitoring_plots plots ON plots.id = totals.monitoring_plot_id
    JOIN tracking.planting_site_histories psh ON psh.id = obs.planting_site_history_id
WHERE NOT EXISTS (
    SELECT 1 FROM tracking.monitoring_plot_histories h
    WHERE h.planting_site_history_id = obs.planting_site_history_id
        AND h.monitoring_plot_id = plots.id
);

UPDATE tracking.observed_plot_species_totals as totals
    SET monitoring_plot_history_id = ph.id
    FROM tracking.observations as obs
    JOIN tracking.monitoring_plot_histories as ph
        ON ph.planting_site_history_id = obs.planting_site_history_id
    WHERE obs.id = totals.observation_id
        AND ph.monitoring_plot_id = totals.monitoring_plot_id;

ALTER TABLE tracking.observed_site_species_totals
    ALTER COLUMN planting_site_history_id SET NOT NULL;

ALTER TABLE tracking.observed_stratum_species_totals
    ALTER COLUMN stratum_history_id SET NOT NULL;

ALTER TABLE tracking.observed_substratum_species_totals
    ALTER COLUMN substratum_history_id SET NOT NULL;

ALTER TABLE tracking.observed_plot_species_totals
    ALTER COLUMN monitoring_plot_history_id SET NOT NULL;
