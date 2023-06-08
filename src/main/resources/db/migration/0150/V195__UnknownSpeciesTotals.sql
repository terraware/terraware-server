DROP TABLE tracking.observed_plot_species_totals;
DROP TABLE tracking.observed_zone_species_totals;
DROP TABLE tracking.observed_site_species_totals;

CREATE PROCEDURE create_totals_table(table_name TEXT, scope_table TEXT, scope_column TEXT) AS $$
    BEGIN
        EXECUTE 'CREATE TABLE tracking.' || table_name || ' (
            observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
            ' || scope_column || ' BIGINT NOT NULL REFERENCES tracking.' || scope_table || ' ON DELETE CASCADE,
            species_id BIGINT REFERENCES species,
            species_name TEXT,
            certainty_id INTEGER NOT NULL REFERENCES tracking.recorded_species_certainties,
            total_live INTEGER NOT NULL DEFAULT 0,
            total_dead INTEGER NOT NULL DEFAULT 0,
            total_existing INTEGER NOT NULL DEFAULT 0,
            total_plants INTEGER NOT NULL DEFAULT 0,
            mortality_rate INTEGER NOT NULL DEFAULT 0,

            CONSTRAINT species_identifier_for_certainty
                CHECK (
                    (certainty_id = 1 AND species_id IS NOT NULL AND species_name IS NULL)
                    OR (certainty_id = 2 AND species_id IS NULL AND species_name IS NOT NULL)
                    OR (certainty_id = 3 AND species_id IS NULL AND species_name IS NULL)
                )
        );';

        EXECUTE 'CREATE UNIQUE INDEX ON tracking.' || table_name || ' (observation_id, ' || scope_column || ', species_id) WHERE species_id IS NOT NULL;';
        EXECUTE 'CREATE UNIQUE INDEX ON tracking.' || table_name || ' (observation_id, ' || scope_column || ', species_name) WHERE species_name IS NOT NULL;';
        EXECUTE 'CREATE UNIQUE INDEX ON tracking.' || table_name || ' (observation_id, ' || scope_column || ') WHERE species_id IS NULL AND species_name IS NULL;';

        EXECUTE 'CREATE INDEX ON tracking.' || table_name || ' (observation_id);';
        EXECUTE 'CREATE INDEX ON tracking.' || table_name || ' (species_id);';
        EXECUTE 'CREATE INDEX ON tracking.' || table_name || ' (' || scope_column || ');';
    END;
$$ LANGUAGE plpgsql;

CALL create_totals_table('observed_plot_species_totals', 'monitoring_plots', 'monitoring_plot_id');
CALL create_totals_table('observed_zone_species_totals', 'planting_zones', 'planting_zone_id');
CALL create_totals_table('observed_site_species_totals', 'planting_sites', 'planting_site_id');

DROP PROCEDURE create_totals_table(table_name TEXT, scope_table TEXT, scope_column TEXT);
