ALTER TABLE tracking.planting_date_requests
    ADD COLUMN manually_fulfilled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tracking.planting_date_requests
    ADD CONSTRAINT planting_date_requests_manually_fulfilled_status
        CHECK (NOT manually_fulfilled OR status_id = 3);
