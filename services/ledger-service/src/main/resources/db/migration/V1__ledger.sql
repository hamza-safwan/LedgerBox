CREATE TABLE financial_accounts (
  id uuid PRIMARY KEY,
  owner_subject uuid,
  public_account_number varchar(20) NOT NULL UNIQUE,
  name varchar(160) NOT NULL,
  account_type varchar(20) NOT NULL CHECK (account_type IN ('ASSET','LIABILITY')),
  status varchar(20) NOT NULL CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
  currency char(3) NOT NULL CHECK (currency = 'USD'),
  posted_balance_minor bigint NOT NULL DEFAULT 0,
  available_balance_minor bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL
);

CREATE UNIQUE INDEX one_customer_usd_account ON financial_accounts(owner_subject,currency) WHERE owner_subject IS NOT NULL;

CREATE TABLE journals (
  id uuid PRIMARY KEY,
  business_reference varchar(180) NOT NULL UNIQUE,
  journal_type varchar(40) NOT NULL,
  description varchar(300) NOT NULL,
  initiated_by varchar(100) NOT NULL,
  correlation_id varchar(100) NOT NULL,
  reversal_of uuid REFERENCES journals(id),
  created_at timestamptz NOT NULL
);

CREATE TABLE postings (
  id uuid PRIMARY KEY,
  journal_id uuid NOT NULL REFERENCES journals(id),
  account_id uuid NOT NULL REFERENCES financial_accounts(id),
  direction varchar(10) NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
  amount_minor bigint NOT NULL CHECK (amount_minor > 0),
  currency char(3) NOT NULL CHECK (currency = 'USD'),
  created_at timestamptz NOT NULL
);
CREATE INDEX postings_account_statement_idx ON postings(account_id,created_at DESC,id DESC);

CREATE TABLE funds_holds (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES financial_accounts(id),
  business_reference varchar(180) NOT NULL UNIQUE,
  amount_minor bigint NOT NULL CHECK (amount_minor > 0),
  status varchar(20) NOT NULL CHECK (status IN ('ACTIVE','RELEASED','CAPTURED')),
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL,
  resolved_at timestamptz
);

CREATE TABLE outbox_events (
  id uuid PRIMARY KEY, aggregate_id uuid NOT NULL, event_type varchar(100) NOT NULL,
  payload jsonb NOT NULL, occurred_at timestamptz NOT NULL, published_at timestamptz, attempts integer NOT NULL DEFAULT 0
);
CREATE INDEX ledger_outbox_pending_idx ON outbox_events(occurred_at) WHERE published_at IS NULL;

CREATE TABLE inbox_events (
  event_id uuid PRIMARY KEY, event_type varchar(100) NOT NULL, processed_at timestamptz NOT NULL
);

CREATE TABLE ledger_audit (
  id bigserial PRIMARY KEY, actor_subject varchar(100) NOT NULL, action varchar(100) NOT NULL,
  resource_id uuid NOT NULL, correlation_id varchar(100) NOT NULL, occurred_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO financial_accounts(id,owner_subject,public_account_number,name,account_type,status,currency,created_at)
VALUES ('00000000-0000-0000-0000-000000000001',NULL,'000000000001','Sandbox settlement cash','ASSET','ACTIVE','USD',now());

CREATE OR REPLACE FUNCTION assert_balanced_journal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE debit_total bigint; credit_total bigint;
BEGIN
  SELECT COALESCE(sum(amount_minor) FILTER (WHERE direction='DEBIT'),0),
         COALESCE(sum(amount_minor) FILTER (WHERE direction='CREDIT'),0)
  INTO debit_total, credit_total FROM postings WHERE journal_id=NEW.journal_id;
  IF debit_total <> credit_total OR debit_total = 0 THEN
    RAISE EXCEPTION 'journal % is unbalanced: debits %, credits %', NEW.journal_id, debit_total, credit_total;
  END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER postings_must_balance
AFTER INSERT ON postings DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION assert_balanced_journal();

CREATE OR REPLACE FUNCTION immutable_posting() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'postings are immutable; create a reversal journal'; END $$;
CREATE TRIGGER postings_immutable BEFORE UPDATE OR DELETE ON postings FOR EACH ROW EXECUTE FUNCTION immutable_posting();

