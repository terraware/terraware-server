ALTER TABLE tracking.plot_t0_density RENAME TO plot_t0_densities;

ALTER TABLE tracking.plot_t0_densities
    RENAME CONSTRAINT plot_t0_density_pkey TO plot_t0_densities_pkey;
ALTER TABLE tracking.plot_t0_densities
    RENAME CONSTRAINT plot_t0_density_created_by_fkey TO plot_t0_densities_created_by_fkey;
ALTER TABLE tracking.plot_t0_densities
    RENAME CONSTRAINT plot_t0_density_modified_by_fkey TO plot_t0_densities_modified_by_fkey;
ALTER TABLE tracking.plot_t0_densities
    RENAME CONSTRAINT plot_t0_density_monitoring_plot_id_fkey TO plot_t0_densities_monitoring_plot_id_fkey;
ALTER TABLE tracking.plot_t0_densities
    RENAME CONSTRAINT plot_t0_density_species_id_fkey TO plot_t0_densities_species_id_fkey;
