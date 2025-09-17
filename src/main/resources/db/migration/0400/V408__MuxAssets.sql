CREATE TABLE mux_asset_statuses (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE mux_assets (
    file_id BIGINT PRIMARY KEY REFERENCES files,
    asset_id TEXT NOT NULL,
    mux_asset_status_id INTEGER NOT NULL REFERENCES mux_asset_statuses,
    playback_id TEXT NOT NULL,
    created_time TIMESTAMP WITH TIME ZONE NOT NULL,
    ready_time TIMESTAMP WITH TIME ZONE,
    error_message TEXT,

    UNIQUE (asset_id),
    UNIQUE (playback_id)
);
