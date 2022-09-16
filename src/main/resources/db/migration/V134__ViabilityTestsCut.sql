ALTER TABLE viability_tests ADD COLUMN seeds_compromised INTEGER;
ALTER TABLE viability_tests ADD COLUMN seeds_empty INTEGER;
ALTER TABLE viability_tests ADD COLUMN seeds_filled INTEGER;

INSERT
INTO viability_tests (accession_id,
                      remaining_grams,
                      remaining_quantity,
                      remaining_units_id,
                      seeds_compromised,
                      seeds_empty,
                      seeds_filled,
                      seeds_sown,
                      test_type)
SELECT id,
       remaining_grams,
       remaining_quantity,
       remaining_units_id,
       cut_test_seeds_compromised,
       cut_test_seeds_empty,
       cut_test_seeds_filled,
       (COALESCE(cut_test_seeds_compromised, 0)
           + COALESCE(cut_test_seeds_empty, 0)
           + COALESCE(cut_test_seeds_filled, 0)) AS seeds_sown,
       3 AS test_type -- 3 is the type ID for cut tests
FROM accessions
WHERE (cut_test_seeds_compromised IS NOT NULL
    OR cut_test_seeds_empty IS NOT NULL
    OR cut_test_seeds_filled IS NOT NULL)
  AND remaining_quantity IS NOT NULL;

WITH system_user AS (SELECT id FROM users WHERE user_type_id = 4)
INSERT
INTO withdrawals (accession_id,
                  created_by,
                  created_time,
                  "date",
                  remaining_grams,
                  remaining_quantity,
                  remaining_units_id,
                  updated_time,
                  viability_test_id)
SELECT vt.accession_id,
       (SELECT id FROM system_user),
       NOW(),
       a.modified_time::DATE,
       vt.remaining_grams,
       vt.remaining_quantity,
       vt.remaining_units_id,
       NOW(),
       vt.id
FROM viability_tests vt
         JOIN accessions a ON vt.accession_id = a.id
WHERE vt.test_type = 3
ON CONFLICT (viability_test_id) DO NOTHING;
