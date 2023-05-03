-- Remove accession collectors with no names, and prevent them from being inserted in the future.
DELETE FROM seedbank.accession_collectors WHERE name SIMILAR TO '\s*';
ALTER TABLE seedbank.accession_collectors ADD CHECK (name NOT SIMILAR TO '\s*');
