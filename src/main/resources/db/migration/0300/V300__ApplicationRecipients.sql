CREATE TABLE accelerator.application_recipients (
     user_id BIGINT NOT NULL REFERENCES users,
     created_by BIGINT NOT NULL REFERENCES users,
     created_time TIMESTAMP WITH TIME ZONE NOT NULL,

     PRIMARY KEY (user_id)
);
