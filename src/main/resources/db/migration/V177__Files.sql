ALTER TABLE nursery.withdrawal_photos RENAME COLUMN photo_id TO file_id;
ALTER TABLE report_photos RENAME COLUMN photo_id TO file_id;
ALTER TABLE seedbank.accession_photos RENAME COLUMN photo_id TO file_id;
ALTER TABLE thumbnails RENAME COLUMN photo_id TO file_id;

ALTER TABLE photos RENAME TO files;
