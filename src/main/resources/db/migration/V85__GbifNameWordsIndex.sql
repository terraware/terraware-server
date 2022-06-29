-- Include both the word and the ID in the index so the query can do an index-only scan.
DROP INDEX gbif_name_words_word_idx;
CREATE INDEX ON gbif_name_words (word, gbif_name_id);
