-- The "Cleaning" state (25) was from an earlier version of the seeds PRD and was removed before
-- the PRD was finalized; it should have been removed from the code as well. There's no value
-- keeping it around in history since it will only have ever been visible in QA testing, not to
-- any actual end users.

UPDATE accessions SET state_id = 20 WHERE state_id = 25;
UPDATE accession_state_history SET old_state_id = 20 WHERE old_state_id = 25;
UPDATE accession_state_history SET new_state_id = 20 WHERE new_state_id = 25;

DELETE FROM accession_states WHERE id = 25;
