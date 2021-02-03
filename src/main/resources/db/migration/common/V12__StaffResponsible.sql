-- Storage and withdrawal now have a "staff responsible" field.
ALTER TABLE accession ADD COLUMN storage_staff_responsible TEXT;
ALTER TABLE withdrawal ADD COLUMN staff_responsible TEXT;

-- Storage end date is now implied by withdrawal of last seed, not entered explicitly.
ALTER TABLE accession DROP COLUMN storage_end_date;
