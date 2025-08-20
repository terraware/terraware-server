CREATE PROCEDURE add_survival_rate_column(table_name TEXT) AS $$
    BEGIN
        EXECUTE 'ALTER TABLE tracking.' || table_name || '
                ADD COLUMN survival_rate INTEGER';
    END;
$$ LANGUAGE plpgsql;

CALL add_survival_rate_column('observed_plot_species_totals');
CALL add_survival_rate_column('observed_subzone_species_totals');
CALL add_survival_rate_column('observed_zone_species_totals');
CALL add_survival_rate_column('observed_site_species_totals');

DROP PROCEDURE add_survival_rate_column;

CREATE TABLE tracking.t0_plot_species (
    monitoring_plot_id BIGINT NOT NULL REFERENCES tracking.monitoring_plots ON DELETE CASCADE,
    species_id BIGINT NOT NULL REFERENCES species,
    observation_id BIGINT REFERENCES tracking.observations ON DELETE CASCADE,
    estimated_planting_density NUMERIC,
    PRIMARY KEY (monitoring_plot_id, species_id),
    CONSTRAINT t0_plot_species_density CHECK ((observation_id IS NULL) != (estimated_planting_density IS NULL))
);
