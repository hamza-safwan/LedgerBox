CREATE TABLE rail_clock (
  id smallint PRIMARY KEY CHECK (id = 1),
  simulated_at timestamptz NOT NULL,
  running boolean NOT NULL,
  updated_at timestamptz NOT NULL
);
INSERT INTO rail_clock(id,simulated_at,running,updated_at) VALUES (1,clock_timestamp(),true,clock_timestamp());

CREATE TABLE settlement_batches (
  id uuid PRIMARY KEY,
  status varchar(20) NOT NULL CHECK (status IN ('OPEN','CLOSED','RECONCILED','EXCEPTION')),
  entry_count integer NOT NULL DEFAULT 0,
  total_deposit_minor bigint NOT NULL DEFAULT 0,
  total_withdrawal_minor bigint NOT NULL DEFAULT 0,
  opened_at timestamptz NOT NULL,
  closed_at timestamptz,
  reconciled_at timestamptz,
  exception_detail varchar(300)
);

ALTER TABLE ach_transfers ADD COLUMN settlement_batch_id uuid REFERENCES settlement_batches(id);

CREATE TABLE settlement_batch_entries (
  batch_id uuid NOT NULL REFERENCES settlement_batches(id),
  ach_transfer_id uuid NOT NULL UNIQUE REFERENCES ach_transfers(id),
  direction varchar(20) NOT NULL CHECK (direction IN ('DEPOSIT','WITHDRAWAL')),
  amount_minor bigint NOT NULL CHECK (amount_minor > 0),
  PRIMARY KEY (batch_id,ach_transfer_id)
);
CREATE INDEX settlement_batches_status_idx ON settlement_batches(status,opened_at);
