ALTER TABLE tracking.observed_plot_species_totals ADD COLUMN cumulative_dead INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tracking.observed_site_species_totals ADD COLUMN cumulative_dead INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tracking.observed_zone_species_totals ADD COLUMN cumulative_dead INTEGER NOT NULL DEFAULT 0;

ALTER TABLE tracking.observed_plot_species_totals ADD COLUMN permanent_live INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tracking.observed_site_species_totals ADD COLUMN permanent_live INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tracking.observed_zone_species_totals ADD COLUMN permanent_live INTEGER NOT NULL DEFAULT 0;

ALTER TABLE tracking.observed_plot_species_totals ALTER COLUMN mortality_rate DROP NOT NULL;
ALTER TABLE tracking.observed_site_species_totals ALTER COLUMN mortality_rate DROP NOT NULL;
ALTER TABLE tracking.observed_zone_species_totals ALTER COLUMN mortality_rate DROP NOT NULL;
