-- Helper functions for conditional column renaming
CREATE OR REPLACE FUNCTION
    column_exists(ptable TEXT, pcolumn TEXT)
    RETURNS BOOLEAN
    LANGUAGE sql
    STABLE STRICT
AS
$body$
    -- does the requested table.column exist in schema?
SELECT EXISTS
           (SELECT NULL
            FROM information_schema.columns
            WHERE table_name = ptable
              AND column_name = pcolumn
           );
$body$;

CREATE OR REPLACE FUNCTION rename_column_if_exists(ptable TEXT, pcolumn TEXT, new_name TEXT)
    RETURNS VOID AS
$BODY$
BEGIN
    -- Rename the column if it exists.
    IF column_exists(ptable, pcolumn) THEN
        EXECUTE FORMAT('ALTER TABLE %I RENAME COLUMN %I TO %I;',
                       ptable, pcolumn, new_name);
    END IF;
END
$BODY$
    LANGUAGE plpgsql VOLATILE;
