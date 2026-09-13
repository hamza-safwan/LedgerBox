CREATE TABLE customer_profiles (
  id uuid PRIMARY KEY,
  user_subject uuid NOT NULL UNIQUE,
  legal_name_ciphertext text NOT NULL,
  date_of_birth_ciphertext text NOT NULL,
  address_ciphertext text NOT NULL,
  key_version integer NOT NULL DEFAULT 1,
  kyc_status varchar(32) NOT NULL CHECK (kyc_status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','MANUAL_REVIEW')),
  kyc_reason varchar(120),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 0
);

CREATE TABLE outbox_events (
  id uuid PRIMARY KEY,
  aggregate_id uuid NOT NULL,
  event_type varchar(100) NOT NULL,
  payload jsonb NOT NULL,
  occurred_at timestamptz NOT NULL,
  published_at timestamptz,
  attempts integer NOT NULL DEFAULT 0
);
CREATE INDEX customer_outbox_pending_idx ON outbox_events(occurred_at) WHERE published_at IS NULL;

CREATE TABLE customer_audit (
  id bigserial PRIMARY KEY,
  actor_subject varchar(100) NOT NULL,
  action varchar(100) NOT NULL,
  resource_id uuid NOT NULL,
  correlation_id varchar(100),
  occurred_at timestamptz NOT NULL DEFAULT now()
);

