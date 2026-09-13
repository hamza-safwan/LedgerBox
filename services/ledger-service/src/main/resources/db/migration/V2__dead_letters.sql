CREATE TABLE dead_letters (
  id uuid PRIMARY KEY,
  source_topic varchar(120) NOT NULL,
  message_key varchar(200),
  payload text NOT NULL,
  payload_hash char(64) NOT NULL UNIQUE,
  received_at timestamptz NOT NULL,
  redriven_at timestamptz,
  redrive_key varchar(100)
);
