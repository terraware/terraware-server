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
    monitoring_plot_id BIGINT PRIMARY KEY REFERENCES tracking.monitoring_plots ON DELETE CASCADE,
    observation_id BIGINT REFERENCES tracking.observations ON DELETE CASCADE,
    species_id BIGINT REFERENCES species ON DELETE CASCADE,
    estimated_planting_density NUMERIC,
    CONSTRAINT t0_plot_species_density CHECK ((observation_id IS NULL) or (estimated_planting_density IS NULL and species_id IS NULL))
);
