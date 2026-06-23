ALTER TABLE seedbank.accessions
    ALTER COLUMN collected_date TYPE TIMESTAMP WITH TIME ZONE
        USING collected_date::TIMESTAMP AT TIME ZONE 'UTC';

ALTER TABLE seedbank.accessions
    RENAME COLUMN collected_date TO collected_time;
