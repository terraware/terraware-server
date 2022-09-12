CREATE TABLE app_versions (
    app_name TEXT NOT NULL,
    platform TEXT NOT NULL,
    minimum_version TEXT NOT NULL,
    recommended_version TEXT NOT NULL,
    PRIMARY KEY (app_name, platform)
);
