ALTER TABLE accelerator.modules ADD COLUMN recorded_session_description TEXT;

-- For recorded sessions, start and end time will be the same.
ALTER TABLE accelerator.events DROP CONSTRAINT times_start_before_end;
ALTER TABLE accelerator.events ADD CONSTRAINT times_start_before_end
    CHECK (start_time <= end_time);
