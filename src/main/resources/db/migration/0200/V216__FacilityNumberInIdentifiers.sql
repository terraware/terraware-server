UPDATE seedbank.accessions a
SET number = regexp_replace(
        number,
        '^(\d\d-1-)(\d+)$',
        (
            SELECT '\1' || facility_number::text || '-\2'
            FROM facilities f
            WHERE f.id = a.facility_id
        )
)
WHERE number ~ '^\d\d-1-\d+$';

UPDATE nursery.batches b
SET batch_number = regexp_replace(
        batch_number,
        '^(\d\d-2-)(\d+)$',
        (
            SELECT '\1' || facility_number::text || '-\2'
            FROM facilities f
            WHERE f.id = b.facility_id
        )
)
WHERE batch_number ~ '^\d\d-2-\d+$';
