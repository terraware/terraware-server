CREATE TABLE birdnet_results (
    file_id BIGINT PRIMARY KEY REFERENCES files,
    created_by BIGINT NOT NULL REFERENCES users,
    created_time TIMESTAMP WITH TIME ZONE NOT NULL,
    asset_status_id INTEGER NOT NULL REFERENCES asset_statuses,
    completed_time TIMESTAMP WITH TIME ZONE,
    results_storage_url TEXT,
    error_message TEXT
);
