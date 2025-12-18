DROP VIEW tracking.planting_site_summaries;
DROP VIEW nursery.withdrawal_summaries;
------
ALTER TABLE tracking.planting_zones RENAME CONSTRAINT planting_zones_pkey TO strata_pkey;
ALTER TABLE tracking.planting_zones RENAME CONSTRAINT planting_zones_planting_site_id_id_key TO strata_planting_site_id_id_key;
ALTER TABLE tracking.planting_zones RENAME CONSTRAINT planting_zones_planting_site_id_name_key TO strata_planting_site_id_name_key;
ALTER TABLE tracking.planting_zones RENAME CONSTRAINT planting_zones_planting_site_id_stable_id_key TO strata_planting_site_id_stable_id_key;
ALTER TABLE tracking.planting_zones RENAME CONSTRAINT planting_zones_boundary_modified_by_fkey TO strata_boundary_modified_by_fkey;
ALTER TABLE tracking.planting_zones RENAME CONSTRAINT planting_zones_created_by_fkey TO strata_created_by_fkey;
ALTER TABLE tracking.planting_zones RENAME CONSTRAINT planting_zones_modified_by_fkey TO strata_modified_by_fkey;
ALTER TABLE tracking.planting_zones RENAME CONSTRAINT planting_zones_planting_site_id_fkey TO strata_planting_site_id_fkey;
ALTER TABLE tracking.planting_zones RENAME TO strata;
------
ALTER TABLE tracking.planting_zone_histories RENAME COLUMN planting_zone_id TO stratum_id;
ALTER TABLE tracking.planting_zone_histories RENAME CONSTRAINT planting_zone_histories_pkey TO stratum_histories_pkey;
ALTER TABLE tracking.planting_zone_histories RENAME CONSTRAINT planting_zone_histories_planting_site_history_id_fkey TO stratum_histories_planting_site_history_id_fkey;
ALTER TABLE tracking.planting_zone_histories RENAME CONSTRAINT planting_zone_histories_planting_zone_id_fkey TO stratum_histories_stratum_id_fkey;
ALTER INDEX tracking.planting_zone_histories_planting_site_history_id_idx RENAME TO stratum_histories_planting_site_history_id_idx;
ALTER INDEX tracking.planting_zone_histories_planting_zone_id_idx RENAME TO stratum_histories_stratum_id_idx;
ALTER TABLE tracking.planting_zone_histories RENAME TO stratum_histories;
------
ALTER TABLE tracking.planting_zone_populations RENAME COLUMN planting_zone_id TO stratum_id;
ALTER TABLE tracking.planting_zone_populations RENAME CONSTRAINT planting_zone_populations_pkey TO stratum_populations_pkey;
ALTER TABLE tracking.planting_zone_populations RENAME CONSTRAINT planting_zone_populations_planting_zone_id_fkey TO stratum_populations_stratum_id_fkey;
ALTER TABLE tracking.planting_zone_populations RENAME CONSTRAINT planting_zone_populations_species_id_fkey TO stratum_populations_species_id_fkey;
ALTER TABLE tracking.planting_zone_populations RENAME TO stratum_populations;
------
ALTER TABLE tracking.observed_zone_species_totals RENAME COLUMN planting_zone_id TO stratum_id;
ALTER TABLE tracking.observed_zone_species_totals RENAME CONSTRAINT observed_zone_species_totals_certainty_id_fkey TO observed_stratum_species_totals_certainty_id_fkey;
ALTER TABLE tracking.observed_zone_species_totals RENAME CONSTRAINT observed_zone_species_totals_observation_id_fkey TO observed_stratum_species_totals_observation_id_fkey;
ALTER TABLE tracking.observed_zone_species_totals RENAME CONSTRAINT observed_zone_species_totals_planting_zone_id_fkey TO observed_stratum_species_totals_stratum_id_fkey;
ALTER TABLE tracking.observed_zone_species_totals RENAME CONSTRAINT observed_zone_species_totals_species_id_fkey TO observed_stratum_species_totals_species_id_fkey;
ALTER INDEX tracking.observed_zone_species_totals_observation_id_idx RENAME TO observed_stratum_species_totals_observation_id_idx;
ALTER INDEX tracking.observed_zone_species_totals_observation_id_planting_zone__idx1 RENAME TO observed_stratum_sp_totals_observation_id_stratum_idx1;
ALTER INDEX tracking.observed_zone_species_totals_observation_id_planting_zone__idx2 RENAME TO observed_stratum_sp_totals_observation_id_stratum_idx2;
ALTER INDEX tracking.observed_zone_species_totals_observation_id_planting_zone_i_idx RENAME TO observed_stratum_sp_totals_observation_id_stratum_idx;
ALTER INDEX tracking.observed_zone_species_totals_planting_zone_id_idx RENAME TO observed_stratum_species_totals_stratum_id_idx;
ALTER INDEX tracking.observed_zone_species_totals_species_id_idx RENAME TO observed_stratum_species_totals_species_id_idx;
ALTER TABLE tracking.observed_zone_species_totals RENAME TO observed_stratum_species_totals;
------
ALTER TABLE tracking.planting_zone_t0_temp_densities RENAME COLUMN planting_zone_id TO stratum_id;
ALTER TABLE tracking.planting_zone_t0_temp_densities RENAME COLUMN zone_density TO stratum_density;
ALTER TABLE tracking.planting_zone_t0_temp_densities RENAME CONSTRAINT planting_zone_t0_temp_densities_pkey TO stratum_t0_temp_densities_pkey;
ALTER TABLE tracking.planting_zone_t0_temp_densities RENAME CONSTRAINT planting_zone_t0_temp_densities_created_by_fkey TO stratum_t0_temp_densities_created_by_fkey;
ALTER TABLE tracking.planting_zone_t0_temp_densities RENAME CONSTRAINT planting_zone_t0_temp_densities_modified_by_fkey TO stratum_t0_temp_densities_modified_by_fkey;
ALTER TABLE tracking.planting_zone_t0_temp_densities RENAME CONSTRAINT planting_zone_t0_temp_densities_planting_zone_id_fkey TO stratum_t0_temp_densities_stratum_id_fkey;
ALTER TABLE tracking.planting_zone_t0_temp_densities RENAME CONSTRAINT planting_zone_t0_temp_densities_species_id_fkey TO stratum_t0_temp_densities_species_id_fkey;
ALTER TABLE tracking.planting_zone_t0_temp_densities RENAME TO stratum_t0_temp_densities;
------
ALTER TABLE tracking.planting_subzones RENAME COLUMN planting_zone_id TO stratum_id;
ALTER TABLE tracking.planting_subzones RENAME CONSTRAINT plots_pkey TO substrata_pkey;
ALTER TABLE tracking.planting_subzones RENAME CONSTRAINT planting_subzones_planting_site_id_stable_id_key TO substrata_planting_site_id_stable_id_key;
ALTER TABLE tracking.planting_subzones RENAME CONSTRAINT plots_planting_site_id_id_key TO substrata_planting_site_id_id_key;
ALTER TABLE tracking.planting_subzones RENAME CONSTRAINT plots_planting_zone_id_name_key TO substrata_stratum_id_name_key;
ALTER TABLE tracking.planting_subzones RENAME CONSTRAINT plots_created_by_fkey TO substrata_created_by_fkey;
ALTER TABLE tracking.planting_subzones RENAME CONSTRAINT plots_modified_by_fkey TO substrata_modified_by_fkey;
ALTER TABLE tracking.planting_subzones RENAME CONSTRAINT plots_planting_site_id_fkey TO substrata_planting_site_id_fkey;
ALTER TABLE tracking.planting_subzones RENAME CONSTRAINT plots_planting_site_id_planting_zone_id_fkey TO substrata_planting_site_id_stratum_id_fkey;
ALTER TABLE tracking.planting_subzones RENAME CONSTRAINT plots_planting_zone_id_fkey TO substrata_stratum_id_fkey;
ALTER TABLE tracking.planting_subzones RENAME TO substrata;
------
ALTER TABLE tracking.planting_subzone_histories RENAME COLUMN planting_zone_history_id TO stratum_history_id;
ALTER TABLE tracking.planting_subzone_histories RENAME COLUMN planting_subzone_id TO substratum_id;
ALTER TABLE tracking.planting_subzone_histories RENAME CONSTRAINT planting_subzone_histories_pkey TO substratum_histories_pkey;
ALTER TABLE tracking.planting_subzone_histories RENAME CONSTRAINT planting_subzone_histories_planting_subzone_id_fkey TO substratum_histories_substratum_id_fkey;
ALTER TABLE tracking.planting_subzone_histories RENAME CONSTRAINT planting_subzone_histories_planting_zone_history_id_fkey TO substratum_histories_stratum_history_id_fkey;
ALTER INDEX tracking.planting_subzone_histories_planting_subzone_id_idx RENAME TO substratum_histories_substratum_id_idx;
ALTER INDEX tracking.planting_subzone_histories_planting_zone_history_id_idx RENAME TO substratum_histories_stratum_history_id_idx;
ALTER TABLE tracking.planting_subzone_histories RENAME TO substratum_histories;
------
ALTER TABLE tracking.planting_subzone_populations RENAME COLUMN planting_subzone_id TO substratum_id;
ALTER TABLE tracking.planting_subzone_populations RENAME CONSTRAINT planting_subzone_populations_pkey TO substratum_populations_pkey;
ALTER TABLE tracking.planting_subzone_populations RENAME CONSTRAINT planting_subzone_populations_planting_subzone_id_fkey TO substratum_populations_substratum_id_fkey;
ALTER TABLE tracking.planting_subzone_populations RENAME CONSTRAINT planting_subzone_populations_species_id_fkey TO substratum_populations_species_id_fkey;
ALTER TABLE tracking.planting_subzone_populations RENAME TO substratum_populations;
------
ALTER TABLE tracking.observed_subzone_species_totals RENAME COLUMN planting_subzone_id TO substratum_id;
ALTER TABLE tracking.observed_subzone_species_totals RENAME CONSTRAINT observed_subzone_species_totals_certainty_id_fkey TO observed_substratum_species_totals_certainty_id_fkey;
ALTER TABLE tracking.observed_subzone_species_totals RENAME CONSTRAINT observed_subzone_species_totals_observation_id_fkey TO observed_substratum_species_totals_observation_id_fkey;
ALTER TABLE tracking.observed_subzone_species_totals RENAME CONSTRAINT observed_subzone_species_totals_planting_subzone_id_fkey TO observed_substratum_species_totals_substratum_id_fkey;
ALTER TABLE tracking.observed_subzone_species_totals RENAME CONSTRAINT observed_subzone_species_totals_species_id_fkey TO observed_substratum_species_totals_species_id_fkey;
ALTER INDEX tracking.observed_subzone_species_tota_observation_id_planting_subz_idx1 RENAME TO observed_substratum_sp_totals_observation_id_substr_idx1;
ALTER INDEX tracking.observed_subzone_species_tota_observation_id_planting_subz_idx2 RENAME TO observed_substratum_sp_totals_observation_id_substr_idx2;
ALTER INDEX tracking.observed_subzone_species_tota_observation_id_planting_subzo_idx RENAME TO observed_substratum_sp_totals_observation_id_substr_idx;
ALTER INDEX tracking.observed_subzone_species_totals_observation_id_idx RENAME TO observed_substratum_species_totals_observation_id_idx;
ALTER INDEX tracking.observed_subzone_species_totals_planting_subzone_id_idx RENAME TO observed_substratum_species_totals_substratum_id_idx;
ALTER INDEX tracking.observed_subzone_species_totals_species_id_idx RENAME TO observed_substratum_species_totals_species_id_idx;
ALTER TABLE tracking.observed_subzone_species_totals RENAME TO observed_substratum_species_totals;
------
ALTER TABLE tracking.observation_requested_subzones RENAME COLUMN planting_subzone_id TO substratum_id;
ALTER TABLE tracking.observation_requested_subzones RENAME CONSTRAINT observation_requested_subzones_pkey TO observation_requested_substrata_pkey;
ALTER TABLE tracking.observation_requested_subzones RENAME CONSTRAINT observation_requested_subzones_observation_id_fkey TO observation_requested_substrata_observation_id_fkey;
ALTER TABLE tracking.observation_requested_subzones RENAME CONSTRAINT observation_requested_subzones_planting_subzone_id_fkey TO observation_requested_substrata_substratum_id_fkey;
ALTER INDEX tracking.observation_requested_subzones_planting_subzone_id_idx RENAME TO observation_requested_substrata_substratum_id_idx;
ALTER TABLE tracking.observation_requested_subzones RENAME TO observation_requested_substrata;
------
ALTER TABLE tracking.monitoring_plot_histories RENAME COLUMN planting_subzone_id TO substratum_id;
ALTER TABLE tracking.monitoring_plot_histories RENAME COLUMN planting_subzone_history_id TO substratum_history_id;
ALTER TABLE tracking.monitoring_plot_histories RENAME CONSTRAINT monitoring_plot_histories_planting_subzone_history_id_fkey TO monitoring_plot_histories_substratum_history_id_fkey;
ALTER TABLE tracking.monitoring_plot_histories RENAME CONSTRAINT monitoring_plot_histories_planting_subzone_id_fkey TO monitoring_plot_histories_substratum_id_fkey;
ALTER INDEX tracking.monitoring_plot_histories_planting_subzone_history_id_idx RENAME TO monitoring_plot_histories_substratum_history_id_idx;
ALTER INDEX tracking.monitoring_plot_histories_planting_subzone_id_idx RENAME TO monitoring_plot_histories_substratum_id_idx;
------
ALTER TABLE tracking.monitoring_plots RENAME COLUMN planting_subzone_id TO substratum_id;
ALTER TABLE tracking.monitoring_plots RENAME CONSTRAINT monitoring_plots_planting_subzone_id_fkey TO monitoring_plots_substratum_id_fkey;
ALTER INDEX tracking.monitoring_plots_planting_subzone_id_idx RENAME TO monitoring_plots_substratum_id_idx;
------
ALTER TABLE tracking.plantings RENAME COLUMN planting_subzone_id TO substratum_id;
ALTER TABLE tracking.plantings RENAME CONSTRAINT plantings_planting_site_id_plot_id_fkey TO plantings_planting_site_id_substratum_id_fkey;
ALTER TABLE tracking.plantings RENAME CONSTRAINT plantings_planting_subzone_id_fkey TO plantings_substratum_id_fkey;
ALTER INDEX tracking.plantings_plot_id_idx RENAME TO plantings_substratum_id_idx;
------
ALTER TABLE tracking.draft_planting_sites RENAME COLUMN num_planting_zones TO num_strata;
ALTER TABLE tracking.draft_planting_sites RENAME COLUMN num_planting_subzones TO num_substrata;
------
CREATE VIEW tracking.planting_site_summaries AS
  SELECT id,
    organization_id,
    name,
    description,
    boundary,
    created_by,
    created_time,
    modified_by,
    modified_time,
    ( SELECT count(*) AS count
           FROM tracking.strata s
          WHERE ps.id = s.planting_site_id) AS num_strata,
    ( SELECT count(*) AS count
           FROM tracking.strata s
             JOIN tracking.substrata ss ON s.id = ss.stratum_id
          WHERE ps.id = s.planting_site_id) AS num_substrata,
    time_zone,
    project_id,
    exclusion,
    country_code
   FROM tracking.planting_sites ps;
------
CREATE VIEW nursery.withdrawal_summaries AS
SELECT withdrawals.id,
       withdrawals.facility_id,
       withdrawals.purpose_id,
       withdrawals.withdrawn_date,
       withdrawals.created_by,
       withdrawals.created_time,
       withdrawals.modified_by,
       withdrawals.modified_time,
       withdrawals.destination_facility_id,
       withdrawals.notes,
       withdrawals.undoes_withdrawal_id,
       undoes_withdrawals.withdrawn_date              as undoes_withdrawal_date,
       undone_by_withdrawals.id                       AS undone_by_withdrawal_id,
       undone_by_withdrawals.withdrawn_date           AS undone_by_withdrawal_date,
       facilities.organization_id,
       deliveries.id                                  AS delivery_id,
       totals.total_withdrawn,
       COALESCE(dest_nurseries.name, dest_sites.name) AS destination_name,
       COALESCE(reassignment_substrata.plot_names,
                delivery_substrata.plot_names)         AS substratum_names,
       (EXISTS (SELECT 1
                FROM tracking.plantings p
                WHERE p.delivery_id = deliveries.id
                  AND p.planting_type_id = 2))        AS has_reassignments
FROM nursery.withdrawals withdrawals
         JOIN facilities ON withdrawals.facility_id = facilities.id
         LEFT JOIN tracking.deliveries deliveries
                   ON withdrawals.id = deliveries.withdrawal_id AND withdrawals.purpose_id = 3
         LEFT JOIN nursery.withdrawals AS undone_by_withdrawals
                   ON withdrawals.id = undone_by_withdrawals.undoes_withdrawal_id
         LEFT JOIN nursery.withdrawals AS undoes_withdrawals
                   ON withdrawals.undoes_withdrawal_id = undoes_withdrawals.id
         LEFT JOIN LATERAL ( SELECT COALESCE(sum(bw.germinating_quantity_withdrawn +
                                                 bw.hardening_off_quantity_withdrawn +
                                                 bw.active_growth_quantity_withdrawn +
                                                 bw.ready_quantity_withdrawn),
                                             0::bigint) AS total_withdrawn
                             FROM nursery.batch_withdrawals bw
                             WHERE withdrawals.id = bw.withdrawal_id) totals ON true
         LEFT JOIN LATERAL ( SELECT string_agg(DISTINCT ss.full_name, ', '::text
                                               ORDER BY ss.full_name) AS plot_names
                             FROM tracking.substrata ss
                                      JOIN tracking.plantings pl ON ss.id = pl.substratum_id
                             WHERE pl.planting_type_id = 1
                               AND pl.delivery_id = deliveries.id) delivery_substrata ON true
         LEFT JOIN LATERAL ( SELECT ((delivery_substrata.plot_names || ' ('::text) ||
                                     string_agg(ss.full_name, ', '::text ORDER BY ss.full_name)) ||
                                    ')'::text AS plot_names
                             FROM tracking.substrata ss
                                      JOIN tracking.plantings pl ON ss.id = pl.substratum_id
                             WHERE pl.planting_type_id = 3
                               AND pl.delivery_id = deliveries.id) reassignment_substrata ON true
         LEFT JOIN LATERAL ( SELECT f.name
                             FROM facilities f
                             WHERE f.id = withdrawals.destination_facility_id
                               AND withdrawals.purpose_id = 1) dest_nurseries ON true
         LEFT JOIN LATERAL ( SELECT ps.name
                             FROM tracking.planting_sites ps
                             WHERE ps.id = deliveries.planting_site_id
                               AND withdrawals.purpose_id = 3) dest_sites ON true;
