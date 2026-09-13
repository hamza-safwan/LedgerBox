ALTER TABLE transfers
  ALTER COLUMN currency TYPE varchar(3),
  ALTER COLUMN request_fingerprint TYPE varchar(64);
