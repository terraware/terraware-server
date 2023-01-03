ALTER TABLE facilities
    ADD COLUMN last_notification_date DATE;
ALTER TABLE facilities
    ADD COLUMN next_notification_time TIMESTAMP WITH TIME ZONE
        NOT NULL
        DEFAULT '1970-01-01T00:00:00Z';

CREATE INDEX ON facilities (next_notification_time);

DROP TABLE task_processed_times;
