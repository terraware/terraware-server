-- Allow a withdrawal to have deliveries at more than one planting site so that plants can be
-- reassigned to other planting sites.

ALTER TABLE tracking.deliveries
    DROP CONSTRAINT deliveries_withdrawal_id_key;

ALTER TABLE tracking.deliveries
    ADD COLUMN reassigned_from_delivery_id BIGINT
        REFERENCES tracking.deliveries ON DELETE SET NULL DEFERRABLE,
    ADD CONSTRAINT deliveries_withdrawal_id_planting_site_id_key
        UNIQUE (withdrawal_id, planting_site_id);

CREATE INDEX ON tracking.deliveries (reassigned_from_delivery_id);

-- Previous version was in V497. A withdrawal can now have more than one delivery, so the view
-- selects the lowest-numbered one. Reassignment substratum names and destination site names
-- are gathered across all of the withdrawal's deliveries.
CREATE OR REPLACE VIEW nursery.withdrawal_summaries AS
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
       undoes_withdrawals.withdrawn_date AS undoes_withdrawal_date,
       undone_by_withdrawals.id AS undone_by_withdrawal_id,
       undone_by_withdrawals.withdrawn_date AS undone_by_withdrawal_date,
       facilities.organization_id,
       deliveries.id AS delivery_id,
       totals.total_withdrawn,
       COALESCE(dest_nurseries.name, dest_sites.name) AS destination_name,
       COALESCE(reassignment_substrata.stratum_names,
                delivery_substrata.stratum_names) AS stratum_names,
       COALESCE(reassignment_substrata.substratum_names,
                delivery_substrata.substratum_names) AS substratum_names,
       COALESCE(reassignment_substrata.substratum_full_names,
                delivery_substrata.substratum_full_names) AS substratum_full_names,
       (EXISTS (SELECT 1
                FROM tracking.plantings p
                WHERE p.delivery_id = deliveries.id
                  AND p.planting_type_id = 2)) AS has_reassignments,
       withdrawals.planting_season_id,
       withdrawals.scheduled_planting_date_request_id
FROM nursery.withdrawals withdrawals
         JOIN facilities ON withdrawals.facility_id = facilities.id
         LEFT JOIN LATERAL ( SELECT d.id
                             FROM tracking.deliveries d
                             WHERE d.withdrawal_id = withdrawals.id
                               AND withdrawals.purpose_id = 3
                             ORDER BY d.id
                             LIMIT 1) deliveries ON true
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
                                               ORDER BY ss.full_name) AS substratum_full_names,
                                    string_agg(DISTINCT ss.name, ', '::text
                                               ORDER BY ss.name) AS substratum_names,
                                    string_agg(DISTINCT s.name, ', '::text
                                               ORDER BY s.name) AS stratum_names
                             FROM tracking.substrata ss
                                      JOIN tracking.plantings pl ON ss.id = pl.substratum_id
                                      JOIN tracking.strata s ON s.id = ss.stratum_id
                             WHERE pl.planting_type_id = 1
                               AND pl.delivery_id = deliveries.id) delivery_substrata ON true
         LEFT JOIN LATERAL ( SELECT ((delivery_substrata.substratum_full_names || ' ('::text) ||
                                     string_agg(ss.full_name, ', '::text ORDER BY ss.full_name)) ||
                                    ')'::text AS substratum_full_names,
                                    ((delivery_substrata.substratum_names || ' ('::text) ||
                                     string_agg(ss.name, ', '::text ORDER BY ss.name)) ||
                                    ')'::text AS substratum_names,
                                    CASE
                                        WHEN string_agg(DISTINCT s.name, ', '::text ORDER BY s.name) IS NOT DISTINCT FROM
                                            delivery_substrata.stratum_names
                                            THEN delivery_substrata.stratum_names
                                        ELSE ((delivery_substrata.stratum_names || ' ('::text) ||
                                              string_agg(DISTINCT s.name, ', '::text ORDER BY s.name)) ||
                                             ')'::text
                                        END   AS stratum_names
                             FROM tracking.substrata ss
                                      JOIN tracking.plantings pl ON ss.id = pl.substratum_id
                                      JOIN tracking.strata s ON s.id = ss.stratum_id
                                      JOIN tracking.deliveries d ON d.id = pl.delivery_id
                             WHERE pl.planting_type_id = 3
                               AND d.withdrawal_id = withdrawals.id
                               AND withdrawals.purpose_id = 3) reassignment_substrata ON true
         LEFT JOIN LATERAL ( SELECT f.name
                             FROM facilities f
                             WHERE f.id = withdrawals.destination_facility_id
                               AND withdrawals.purpose_id = 1) dest_nurseries ON true
         LEFT JOIN LATERAL ( SELECT string_agg(DISTINCT ps.name, ', '::text
                                               ORDER BY ps.name) AS name
                             FROM tracking.planting_sites ps
                                      JOIN tracking.deliveries d ON ps.id = d.planting_site_id
                             WHERE d.withdrawal_id = withdrawals.id
                               AND withdrawals.purpose_id = 3) dest_sites ON true;
