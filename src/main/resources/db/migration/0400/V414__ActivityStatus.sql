CREATE TABLE accelerator.activity_statuses (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

INSERT INTO accelerator.activity_statuses (id, name)
VALUES (1, 'Not Verified'),
       (2, 'Verified'),
       (3, 'Do Not Use')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

ALTER TABLE accelerator.activities ADD COLUMN activity_status_id INTEGER REFERENCES accelerator.activity_statuses;

UPDATE accelerator.activities
SET activity_status_id = CASE
    WHEN verified_by IS NULL THEN 1
    ELSE 2
END;

ALTER TABLE accelerator.activities
    ALTER COLUMN activity_status_id SET NOT NULL;

ALTER TABLE accelerator.activities
    ADD CONSTRAINT activity_status_verified
    CHECK (
        (verified_by IS NULL AND activity_status_id = 1)
            OR (verified_by IS NOT NULL AND activity_status_id = 2)
            OR (activity_status_id = 3)
        );
