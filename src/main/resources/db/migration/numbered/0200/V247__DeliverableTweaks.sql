ALTER TABLE accelerator.deliverables DROP COLUMN subtitle;

ALTER TABLE accelerator.deliverable_documents ADD PRIMARY KEY (deliverable_id);

ALTER TABLE accelerator.submission_documents ADD COLUMN original_name TEXT;
