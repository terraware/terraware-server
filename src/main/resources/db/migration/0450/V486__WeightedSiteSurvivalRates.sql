-- Recomputes observation_site_results.survival_rate using an area-weighted average of stratum
-- survival rates.
--
-- This migration mirrors the SQL in ObservationResultsScope.SiteScope.survivalRateValue:
--   * The site SR is null if ANY observation_stratum_results row for the site's planting
--     site history has a null survival_rate.
--   * Otherwise it is SUM(area_ha * stratum_survival_rate) / SUM(area_ha), aggregated over
--     all (stratum, substratum) pairs in the site history where the stratum has a non-null
--     survival_rate and the substratum has been observed in this or any earlier (non-ad-hoc,
--     completed) observation.

WITH qualifying_substrata AS (
    SELECT
        site_r.observation_id,
        site_r.planting_site_history_id,
        stratum_r.survival_rate AS stratum_survival_rate,
        ssh.area_ha AS substratum_area_ha
    FROM tracking.observation_site_results site_r
    JOIN tracking.stratum_histories sh
        ON sh.planting_site_history_id = site_r.planting_site_history_id
    JOIN tracking.substratum_histories ssh
        ON ssh.stratum_history_id = sh.id
    JOIN tracking.observation_stratum_results stratum_r
        ON stratum_r.observation_id = site_r.observation_id
       AND stratum_r.stratum_history_id = sh.id
    WHERE stratum_r.survival_rate IS NOT NULL
      AND EXISTS (
          -- substratum has been observed in this or any earlier non-ad-hoc, completed observation.
          SELECT 1
          FROM tracking.observation_requested_substrata ors
          JOIN tracking.observations earlier
              ON earlier.id = ors.observation_id
          JOIN tracking.observations this_obs
              ON this_obs.id = site_r.observation_id
          WHERE ors.substratum_id = ssh.substratum_id
            AND earlier.is_ad_hoc IS FALSE
            AND earlier.completed_time IS NOT NULL
            AND earlier.completed_time <= this_obs.completed_time
      )
),
aggregates AS (
    SELECT
        observation_id,
        planting_site_history_id,
        SUM(substratum_area_ha * stratum_survival_rate) AS numerator,
        SUM(substratum_area_ha) AS denominator
    FROM qualifying_substrata
    GROUP BY observation_id, planting_site_history_id
)
UPDATE tracking.observation_site_results osr
SET survival_rate = CASE
        -- Null if any stratum in this site_history (for this observation) has null SR.
        WHEN EXISTS (
            SELECT 1
            FROM tracking.observation_stratum_results stratum_r
            JOIN tracking.stratum_histories sh
                ON sh.id = stratum_r.stratum_history_id
            WHERE stratum_r.observation_id = osr.observation_id
              AND sh.planting_site_history_id = osr.planting_site_history_id
              AND stratum_r.survival_rate IS NULL
        ) THEN NULL
        WHEN a.denominator IS NULL OR a.denominator = 0 THEN NULL
        ELSE (a.numerator / a.denominator)::int
    END
FROM aggregates a
WHERE osr.observation_id = a.observation_id
  AND osr.planting_site_history_id = a.planting_site_history_id;

-- Rows with no qualifying substrata at all (so no aggregates row was produced) should
-- have null survival rates. The UPDATE above won't touch them, so handle them separately.
UPDATE tracking.observation_site_results osr
SET survival_rate = NULL
WHERE survival_rate IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM tracking.observation_stratum_results stratum_r
      JOIN tracking.stratum_histories sh
          ON sh.id = stratum_r.stratum_history_id
      JOIN tracking.substratum_histories ssh
          ON ssh.stratum_history_id = sh.id
      WHERE stratum_r.observation_id = osr.observation_id
        AND sh.planting_site_history_id = osr.planting_site_history_id
        AND stratum_r.survival_rate IS NOT NULL
        AND EXISTS (
            SELECT 1
            FROM tracking.observation_requested_substrata ors
            JOIN tracking.observations earlier
                ON earlier.id = ors.observation_id
            JOIN tracking.observations this_obs
                ON this_obs.id = osr.observation_id
            WHERE ors.substratum_id = ssh.substratum_id
              AND earlier.is_ad_hoc IS FALSE
              AND earlier.completed_time IS NOT NULL
              AND earlier.completed_time <= this_obs.completed_time
        )
  );
