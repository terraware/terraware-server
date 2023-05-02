ALTER TABLE accessions ADD COLUMN collection_site_city TEXT;
ALTER TABLE accessions ADD COLUMN collection_site_country_code TEXT REFERENCES countries (code);
ALTER TABLE accessions ADD COLUMN collection_site_country_subdivision TEXT;
ALTER TABLE accessions ADD COLUMN collection_site_notes TEXT;
