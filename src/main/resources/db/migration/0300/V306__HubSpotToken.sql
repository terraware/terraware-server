CREATE TABLE accelerator.hubspot_token (
    refresh_token TEXT NOT NULL
);

-- Only allow one row in the table.
CREATE UNIQUE INDEX ON accelerator.hubspot_token ((0));
