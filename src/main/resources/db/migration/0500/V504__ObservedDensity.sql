ALTER TABLE tracking.observation_stratum_results
    ADD COLUMN observed_density INTEGER;

ALTER TABLE tracking.observation_site_results
    ADD COLUMN observed_density INTEGER;
