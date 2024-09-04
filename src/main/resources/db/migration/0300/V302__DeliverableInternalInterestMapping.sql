ALTER TABLE accelerator.deliverable_categories
    ADD COLUMN internal_interest_id INTEGER REFERENCES accelerator.internal_interests ON DELETE RESTRICT;

-- These are also in R__TypeCodes.sql, but need to be here so the referenced interest IDs exist
-- when we update deliverable_categories.
INSERT INTO accelerator.internal_interests (id, name)
VALUES (1, 'Compliance'),
       (2, 'Financial Viability'),
       (3, 'GIS'),
       (4, 'Carbon Eligibility'),
       (5, 'Stakeholders and Community Impact'),
       (6, 'Proposed Restoration Activities'),
       (7, 'Verra Non-Permanence Risk Tool (NPRT)'),
       (8, 'Supplemental Files'),
       (101, 'Sourcing')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

UPDATE accelerator.deliverable_categories
SET internal_interest_id = id;

ALTER TABLE accelerator.deliverable_categories ALTER COLUMN internal_interest_id SET NOT NULL;
