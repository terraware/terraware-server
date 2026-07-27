-- Backfill accession_collectors from the primary and secondary collectors of existing accessions.
--
-- For each accession, we want a list of names with the primary collector first, followed by the
-- secondary collectors in order of their IDs. We treat the primary collector as having an ID of -1
-- so it always sorts to the top.
--
-- Accessions that were written by the previous version of the code will already have populated
-- accession_collectors, so it's safe to ignore any conflicts.

WITH combined AS (SELECT accession_id, id, name
                  FROM accession_secondary_collectors
                  UNION
                  SELECT id AS accession_id, -1 AS id, primary_collector_name AS name
                  FROM accessions
                  WHERE primary_collector_name IS NOT NULL)
INSERT INTO accession_collectors (accession_id, position, name)
SELECT accession_id, rownum - 1, name
FROM (SELECT accession_id,
             ROW_NUMBER() OVER (PARTITION BY accession_id ORDER BY id) AS rownum,
             name
      FROM combined) sc
ON CONFLICT DO NOTHING;
