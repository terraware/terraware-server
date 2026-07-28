CREATE VIEW nursery.withdrawal_summaries AS
SELECT withdrawals.*,
       facilities.organization_id,
       deliveries.id                                                      AS delivery_id,
       totals.total_withdrawn,
       COALESCE(dest_nurseries.name, dest_sites.name)                     AS destination_name,
       COALESCE(reassignment_plots.plot_names, delivery_plots.plot_names) AS plot_names,
       EXISTS(SELECT 1
              FROM tracking.plantings p
              WHERE p.delivery_id = deliveries.id
                AND p.planting_type_id = 2)                               AS has_reassignments
FROM nursery.withdrawals withdrawals
         JOIN facilities ON withdrawals.facility_id = facilities.id
         LEFT JOIN tracking.deliveries deliveries
                   ON withdrawals.id = deliveries.withdrawal_id
                       AND withdrawals.purpose_id = 3
         LEFT JOIN LATERAL (SELECT COALESCE(SUM(germinating_quantity_withdrawn +
                                                not_ready_quantity_withdrawn +
                                                ready_quantity_withdrawn), 0) AS total_withdrawn
                            FROM nursery.batch_withdrawals bw
                            WHERE withdrawals.id = bw.withdrawal_id) totals ON TRUE
         LEFT JOIN LATERAL (SELECT STRING_AGG(DISTINCT p.full_name, ', '
                                              ORDER BY p.full_name) AS plot_names
                            FROM tracking.plots p
                                     JOIN tracking.plantings pl ON p.id = pl.plot_id
                            WHERE pl.planting_type_id = 1
                              AND pl.delivery_id = deliveries.id) delivery_plots ON TRUE
         LEFT JOIN LATERAL (SELECT delivery_plots.plot_names
                                       || ' ('
                                       || STRING_AGG(p.full_name, ', ' ORDER BY p.full_name)
                                       || ')'
                                       AS plot_names
                            FROM tracking.plots p
                                     JOIN tracking.plantings pl ON p.id = pl.plot_id
                            WHERE pl.planting_type_id = 3
                              AND pl.delivery_id = deliveries.id) reassignment_plots ON TRUE
         LEFT JOIN LATERAL (SELECT f.name
                            FROM facilities f
                            WHERE f.id = withdrawals.destination_facility_id
                              AND withdrawals.purpose_id = 1) dest_nurseries ON TRUE
         LEFT JOIN LATERAL (SELECT ps.name
                            FROM tracking.planting_sites ps
                            WHERE ps.id = deliveries.planting_site_id
                              AND withdrawals.purpose_id = 3) dest_sites ON TRUE;
