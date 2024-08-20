-- Add country_code column
ALTER TABLE accelerator.applications ADD country_code TEXT REFERENCES countries (code);

-- Migrate existing columns by looking up first 3 of internal name
UPDATE accelerator.applications
SET country_code = countries.code
FROM countries
WHERE SUBSTR(accelerator.applications.internal_name, 1, 3) = countries.code_alpha3
  AND SUBSTR(accelerator.applications.internal_name, 1, 3) != 'XXX';
