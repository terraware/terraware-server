-- Add scoring records
CREATE TABLE accelerator.default_voters (
    user_id BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
    PRIMARY KEY (user_id)
);

