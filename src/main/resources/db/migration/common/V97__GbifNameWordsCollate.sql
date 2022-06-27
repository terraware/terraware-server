-- Explicitly select the "C" collation for the GBIF name words index so it will support prefix
-- lookups using LIKE.
DROP INDEX gbif_name_words_word_gbif_name_id_idx;
ALTER TABLE gbif_name_words ALTER COLUMN word SET DATA TYPE TEXT COLLATE "C";
CREATE INDEX ON gbif_name_words (word, gbif_name_id);
