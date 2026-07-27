-- Identifier sequences weren't being deleted when their organizations were deleted.
ALTER TABLE identifier_sequences DROP CONSTRAINT identifier_sequences_organization_id_fkey;

ALTER TABLE identifier_sequences ADD FOREIGN KEY (organization_id) REFERENCES organizations ON DELETE CASCADE;
