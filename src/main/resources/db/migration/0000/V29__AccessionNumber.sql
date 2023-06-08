-- Sequence for date-based accession numbers. See AccessionStore.generateAccessionNumber() for
-- details of how this is used.
CREATE SEQUENCE accession_number_seq;

COMMENT ON SEQUENCE accession_number_seq IS
    'Next date-based accession number. The value is of the form YYYYMMDDXXXXXXXXXX where XXXXXXXXXX starts at 0000000000 at the start of each day. The application resets the sequence as needed.';
