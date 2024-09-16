CREATE TABLE rate_limited_events (
    event_class TEXT NOT NULL,
    rate_limit_key JSONB NOT NULL,
    next_time TIMESTAMP WITH TIME ZONE NOT NULL,
    pending_event JSONB,

    PRIMARY KEY (event_class, rate_limit_key)
);
