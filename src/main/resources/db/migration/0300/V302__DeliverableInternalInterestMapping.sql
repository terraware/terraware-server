ALTER TABLE accelerator.deliverable_categories
    ADD COLUMN internal_interest_id INTEGER REFERENCES accelerator.internal_interests ON DELETE RESTRICT;

UPDATE accelerator.deliverable_categories
SET internal_interest_id = id;

ALTER TABLE accelerator.deliverable_categories ALTER COLUMN internal_interest_id SET NOT NULL;
