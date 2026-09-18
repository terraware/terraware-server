ALTER TABLE seedbank.accessions ALTER COLUMN est_seed_count TYPE BIGINT;
ALTER TABLE seedbank.accessions ALTER COLUMN total_withdrawn_count TYPE BIGINT;
ALTER TABLE seedbank.withdrawals ALTER COLUMN estimated_count TYPE BIGINT;

-- The columns above were INTEGER, but the seed counts stored in them are derived from NUMERIC
-- quantities with no upper bound, so an accession holding more than 2^31-1 seeds had its counts
-- silently truncated to their low 32 bits. Recompute the affected rows from the quantities they
-- were derived from, which were never truncated. Only a row whose correct value falls outside the
-- INTEGER range could have been affected, so leave every other row untouched.

CREATE TEMPORARY VIEW recomputed_withdrawal_counts AS
SELECT w.id,
       round(CASE
               WHEN w.withdrawn_units_id = (SELECT id
                                            FROM seedbank.seed_quantity_units
                                            WHERE name = 'Seeds') THEN w.withdrawn_quantity
               ELSE w.withdrawn_grams * a.subset_count / a.subset_weight_grams
             END) AS estimated_count
FROM seedbank.withdrawals w
       JOIN seedbank.accessions a ON a.id = w.accession_id
WHERE w.withdrawn_quantity IS NOT NULL
  AND (w.withdrawn_units_id = (SELECT id FROM seedbank.seed_quantity_units WHERE name = 'Seeds')
  OR (w.withdrawn_grams IS NOT NULL AND a.subset_count IS NOT NULL AND a.subset_weight_grams > 0));

UPDATE seedbank.withdrawals w
SET estimated_count = r.estimated_count
FROM recomputed_withdrawal_counts r
WHERE w.id = r.id
  AND r.estimated_count > 2147483647;

-- Run after the withdrawals are repaired so the sum is taken over correct values.
UPDATE seedbank.accessions a
SET total_withdrawn_count = totals.total
FROM (SELECT accession_id, sum(estimated_count) AS total
      FROM seedbank.withdrawals
      WHERE estimated_count IS NOT NULL
      GROUP BY accession_id) totals
WHERE a.id = totals.accession_id
  AND totals.total > 2147483647;

CREATE TEMPORARY VIEW recomputed_seed_counts AS
SELECT id,
       round(CASE
               WHEN remaining_units_id = (SELECT id
                                          FROM seedbank.seed_quantity_units
                                          WHERE name = 'Seeds') THEN remaining_quantity
               ELSE remaining_grams * subset_count / subset_weight_grams
             END) AS est_seed_count
FROM seedbank.accessions
WHERE remaining_quantity IS NOT NULL
  AND (remaining_units_id = (SELECT id FROM seedbank.seed_quantity_units WHERE name = 'Seeds')
  OR (remaining_grams IS NOT NULL AND subset_count IS NOT NULL AND subset_weight_grams > 0));

UPDATE seedbank.accessions a
SET est_seed_count = r.est_seed_count
FROM recomputed_seed_counts r
WHERE a.id = r.id
  AND r.est_seed_count > 2147483647;

DROP VIEW recomputed_seed_counts;
DROP VIEW recomputed_withdrawal_counts;
