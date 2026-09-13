CREATE TABLE transfers (
  id uuid PRIMARY KEY,
  user_subject uuid NOT NULL,
  source_account_id uuid NOT NULL,
  destination_account_number varchar(20) NOT NULL,
  amount_minor bigint NOT NULL CHECK (amount_minor > 0),
  currency char(3) NOT NULL CHECK (currency='USD'),
  status varchar(24) NOT NULL CHECK (status IN ('RECEIVED','PROCESSING','POSTED','FAILED')),
  failure_code varchar(80),
  journal_id uuid,
  idempotency_key varchar(100) NOT NULL,
  request_fingerprint char(64) NOT NULL,
  correlation_id varchar(100) NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  UNIQUE(user_subject,idempotency_key)
);
CREATE INDEX transfers_user_created_idx ON transfers(user_subject,created_at DESC);
CREATE INDEX transfers_processing_idx ON transfers(updated_at) WHERE status='PROCESSING';

CREATE TABLE transfer_timeline (
  id bigserial PRIMARY KEY, transfer_id uuid NOT NULL REFERENCES transfers(id),
  stage varchar(80) NOT NULL, detail varchar(300), correlation_id varchar(100) NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE outbox_events (
 id uuid PRIMARY KEY,aggregate_id uuid NOT NULL,event_type varchar(100) NOT NULL,payload jsonb NOT NULL,
 occurred_at timestamptz NOT NULL,published_at timestamptz,attempts integer NOT NULL DEFAULT 0
);
CREATE INDEX movement_outbox_pending_idx ON outbox_events(occurred_at) WHERE published_at IS NULL;
CREATE TABLE inbox_events(event_id uuid PRIMARY KEY,event_type varchar(100) NOT NULL,processed_at timestamptz NOT NULL);

