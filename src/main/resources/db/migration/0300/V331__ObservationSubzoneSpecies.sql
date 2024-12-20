CREATE TABLE tracking.observed_subzone_species_totals (
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    planting_subzone_id BIGINT NOT NULL REFERENCES tracking.planting_subzones ON DELETE CASCADE,
    species_id BIGINT REFERENCES species,
    species_name TEXT,
    certainty_id INTEGER NOT NULL REFERENCES tracking.recorded_species_certainties,
    total_live INTEGER NOT NULL DEFAULT 0,
    total_dead INTEGER NOT NULL DEFAULT 0,
    total_existing INTEGER NOT NULL DEFAULT 0,
    mortality_rate INTEGER,
    cumulative_dead INTEGER NOT NULL DEFAULT 0,
    permanent_live INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT species_identifier_for_certainty
    CHECK (
        (certainty_id = 1 AND species_id IS NOT NULL AND species_name IS NULL)
            OR (certainty_id = 2 AND species_id IS NULL AND species_name IS NOT NULL)
            OR (certainty_id = 3 AND species_id IS NULL AND species_name IS NULL)
        )
);

CREATE UNIQUE INDEX ON tracking.observed_subzone_species_totals (observation_id, planting_subzone_id, species_id) WHERE species_id IS NOT NULL;
CREATE UNIQUE INDEX ON tracking.observed_subzone_species_totals (observation_id, planting_subzone_id, species_name) WHERE species_name IS NOT NULL;
CREATE UNIQUE INDEX ON tracking.observed_subzone_species_totals (observation_id, planting_subzone_id) WHERE species_id IS NULL AND species_name IS NULL;
CREATE INDEX ON tracking.observed_subzone_species_totals (planting_subzone_id);

CREATE INDEX ON tracking.observed_subzone_species_totals (observation_id);
CREATE INDEX ON tracking.observed_subzone_species_totals (species_id);
CREATE INDEX ON tracking.observed_subzone_species_totals (planting_subzone_id);

INSERT INTO tracking.observed_subzone_species_totals(
        observation_id, planting_subzone_id, species_id, species_name, certainty_id,
        total_live, total_dead, total_existing, mortality_rate, cumulative_dead, permanent_live)
    SELECT
        observation_id, plots.planting_subzone_id, species_id, species_name, certainty_id,
        SUM(total_live), SUM(total_dead), SUM(total_existing), null, SUM(cumulative_dead), SUM(permanent_live)
    FROM tracking.observed_plot_species_totals
    JOIN tracking.monitoring_plots plots
    ON plots.id = monitoring_plot_id
    GROUP BY (observation_id, plots.planting_subzone_id, species_id, certainty_id, species_name);

UPDATE tracking.observed_subzone_species_totals
SET mortality_rate =
    case
        when cumulative_dead + permanent_live = 0 then null
        else cumulative_dead / (cumulative_dead + permanent_live)
    end;
