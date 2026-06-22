CREATE TABLE tracking.planting_season_notification_pages (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

-- The primary key gains page_id and changes shape, so recreate the table rather than altering
-- it in place. There are no rows worth preserving yet.
DROP TABLE tracking.planting_season_notifications;

CREATE TABLE tracking.planting_season_notifications (
    planting_season_id BIGINT NOT NULL REFERENCES tracking.planting_seasons ON DELETE CASCADE,
    page_id INTEGER NOT NULL REFERENCES tracking.planting_season_notification_pages,
    last_dismissed_event_log_id BIGINT NOT NULL REFERENCES event_log,
    PRIMARY KEY (planting_season_id, page_id)
);
