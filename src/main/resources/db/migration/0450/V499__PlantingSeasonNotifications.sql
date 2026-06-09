CREATE TABLE tracking.planting_season_notifications (
    planting_season_id BIGINT PRIMARY KEY REFERENCES tracking.planting_seasons ON DELETE CASCADE,
    last_dismissed_time TIMESTAMP WITH TIME ZONE NOT NULL
);
