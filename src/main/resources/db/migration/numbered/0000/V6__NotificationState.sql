-- Notifications about accession states need to link to a search for that specific state, so we
-- need to track which state they're about.
ALTER TABLE notification ADD COLUMN accession_state_id INTEGER REFERENCES accession_state;
COMMENT ON COLUMN notification.accession_state_id
    IS 'For state notifications, which state is being notified about. Null otherwise.';
