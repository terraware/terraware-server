-- Add event status column to events
ALTER TABLE accelerator.events ADD COLUMN event_status_id INTEGER REFERENCES accelerator.event_statuses;

-- Default to ENDED because these events will not have jobs scheduled to update event status
UPDATE accelerator.events SET event_status_id = 4;
ALTER TABLE accelerator.events ALTER COLUMN event_status_id SET NOT NULL;

-- Non null constraints already exist in the controllers
ALTER TABLE accelerator.events ALTER COLUMN start_time SET NOT NULL;
ALTER TABLE accelerator.events ALTER COLUMN end_time SET NOT NULL;
