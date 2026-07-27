ALTER TABLE reports DROP CONSTRAINT one_org_report_per_quarter;
ALTER TABLE reports ADD CONSTRAINT one_org_report_per_quarter
    EXCLUDE (organization_id WITH =, year WITH =, quarter WITH =)
    WHERE (project_id IS NULL AND project_name IS NULL);
