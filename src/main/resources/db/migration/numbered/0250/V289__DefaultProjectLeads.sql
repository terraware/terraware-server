CREATE TABLE accelerator.default_project_leads (
    region_id INTEGER PRIMARY KEY REFERENCES regions,
    project_lead TEXT
);
