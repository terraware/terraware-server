CREATE TABLE tracking.planting_season_notifications (
    planting_season_id BIGINT PRIMARY KEY REFERENCES tracking.planting_seasons ON DELETE CASCADE,
    last_dismissed_event_log_id BIGINT NOT NULL REFERENCES event_log
);
