ALTER TABLE accelerator.activities DROP CONSTRAINT activity_status_verified;

ALTER TABLE accelerator.activities ADD CONSTRAINT activity_status_verified
    CHECK (activity_status_id <> 2 OR verified_by IS NOT NULL);
