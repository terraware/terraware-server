ALTER TABLE accelerator.deliverable_categories
    ADD COLUMN internal_interest_id INTEGER REFERENCES accelerator.internal_interests ON DELETE SET NULL;

UPDATE accelerator.deliverable_categories
SET internal_interest_id = id;
