ALTER TABLE external_accounts
  ALTER COLUMN masked_number TYPE varchar(4);

ALTER TABLE ach_transfers
  ALTER COLUMN currency TYPE varchar(3),
  ALTER COLUMN request_fingerprint TYPE varchar(64);
