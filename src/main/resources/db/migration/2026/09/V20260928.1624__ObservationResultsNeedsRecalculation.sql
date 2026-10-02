ALTER TABLE tracking.observation_plot_results
    ADD COLUMN needs_recalculation BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tracking.observation_substratum_results
    ADD COLUMN needs_recalculation BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tracking.observation_stratum_results
    ADD COLUMN needs_recalculation BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tracking.observation_site_results
    ADD COLUMN needs_recalculation BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX ON tracking.observation_plot_results (observation_id) WHERE needs_recalculation;
CREATE INDEX ON tracking.observation_substratum_results (observation_id) WHERE needs_recalculation;
CREATE INDEX ON tracking.observation_stratum_results (observation_id) WHERE needs_recalculation;
CREATE INDEX ON tracking.observation_site_results (observation_id) WHERE needs_recalculation;
