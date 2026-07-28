DROP VIEW nursery.withdrawal_summaries;

-- Add undoes_withdrawal_date and undone_by_withdrawal_date
-- Previous version was in V259
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
       undoes_withdrawals.withdrawn_date as undoes_withdrawal_date,
       undone_by_withdrawals.id AS undone_by_withdrawal_id,
       undone_by_withdrawals.withdrawn_date AS undone_by_withdrawal_date,
       facilities.organization_id,
       deliveries.id AS delivery_id,
       totals.total_withdrawn,
       COALESCE(dest_nurseries.name, dest_sites.name) AS destination_name,
       COALESCE(reassignment_subzones.plot_names, delivery_subzones.plot_names) AS planting_subzone_names,
       (EXISTS ( SELECT 1
                 FROM tracking.plantings p
                 WHERE p.delivery_id = deliveries.id AND p.planting_type_id = 2)) AS has_reassignments
FROM nursery.withdrawals withdrawals
         JOIN facilities ON withdrawals.facility_id = facilities.id
         LEFT JOIN tracking.deliveries deliveries ON withdrawals.id = deliveries.withdrawal_id AND withdrawals.purpose_id = 3
         LEFT JOIN nursery.withdrawals AS undone_by_withdrawals ON withdrawals.id = undone_by_withdrawals.undoes_withdrawal_id
         LEFT JOIN nursery.withdrawals AS undoes_withdrawals ON withdrawals.undoes_withdrawal_id = undoes_withdrawals.id
         LEFT JOIN LATERAL ( SELECT COALESCE(sum(bw.germinating_quantity_withdrawn + bw.not_ready_quantity_withdrawn + bw.ready_quantity_withdrawn), 0::bigint) AS total_withdrawn
                             FROM nursery.batch_withdrawals bw
                             WHERE withdrawals.id = bw.withdrawal_id) totals ON true
         LEFT JOIN LATERAL ( SELECT string_agg(DISTINCT p.full_name, ', '::text ORDER BY p.full_name) AS plot_names
                             FROM tracking.planting_subzones p
                                      JOIN tracking.plantings pl ON p.id = pl.planting_subzone_id
                             WHERE pl.planting_type_id = 1 AND pl.delivery_id = deliveries.id) delivery_subzones ON true
         LEFT JOIN LATERAL ( SELECT ((delivery_subzones.plot_names || ' ('::text) || string_agg(p.full_name, ', '::text ORDER BY p.full_name)) || ')'::text AS plot_names
                             FROM tracking.planting_subzones p
                                      JOIN tracking.plantings pl ON p.id = pl.planting_subzone_id
                             WHERE pl.planting_type_id = 3 AND pl.delivery_id = deliveries.id) reassignment_subzones ON true
         LEFT JOIN LATERAL ( SELECT f.name
                             FROM facilities f
                             WHERE f.id = withdrawals.destination_facility_id AND withdrawals.purpose_id = 1) dest_nurseries ON true
         LEFT JOIN LATERAL ( SELECT ps.name
                             FROM tracking.planting_sites ps
                             WHERE ps.id = deliveries.planting_site_id AND withdrawals.purpose_id = 3) dest_sites ON true;
