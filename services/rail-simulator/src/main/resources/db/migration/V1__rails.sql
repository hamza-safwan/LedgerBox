CREATE TABLE external_accounts(
 id uuid PRIMARY KEY,user_subject uuid NOT NULL,institution_name varchar(100) NOT NULL,masked_number char(4) NOT NULL,
 status varchar(20) NOT NULL CHECK(status IN('VERIFIED','DISABLED')),created_at timestamptz NOT NULL
);
CREATE INDEX external_accounts_owner_idx ON external_accounts(user_subject,created_at);
CREATE TABLE ach_transfers(
 id uuid PRIMARY KEY,user_subject uuid NOT NULL,ledger_account_id uuid NOT NULL,external_account_id uuid NOT NULL REFERENCES external_accounts(id),
 direction varchar(20) NOT NULL CHECK(direction IN('DEPOSIT','WITHDRAWAL')),amount_minor bigint NOT NULL CHECK(amount_minor>0),currency char(3) NOT NULL CHECK(currency='USD'),
 scenario varchar(20) NOT NULL CHECK(scenario IN('SETTLE','REJECT','RETURN')),status varchar(24) NOT NULL CHECK(status IN('PROCESSING','INITIATED','SUBMITTED','SETTLED','REJECTED','RETURNED')),
 journal_id uuid,idempotency_key varchar(100) NOT NULL,request_fingerprint char(64) NOT NULL,correlation_id varchar(100) NOT NULL,next_transition_at timestamptz NOT NULL,
 failure_detail varchar(200),created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,version bigint NOT NULL DEFAULT 0,UNIQUE(user_subject,idempotency_key)
);
CREATE INDEX ach_due_idx ON ach_transfers(next_transition_at) WHERE status IN('PROCESSING','INITIATED','SUBMITTED','SETTLED');
CREATE TABLE rail_timeline(id bigserial PRIMARY KEY,transfer_id uuid NOT NULL REFERENCES ach_transfers(id),stage varchar(50) NOT NULL,detail varchar(300),occurred_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE outbox_events(id uuid PRIMARY KEY,aggregate_id uuid NOT NULL,event_type varchar(100) NOT NULL,payload jsonb NOT NULL,occurred_at timestamptz NOT NULL,published_at timestamptz,attempts integer NOT NULL DEFAULT 0);
CREATE INDEX rail_outbox_pending_idx ON outbox_events(occurred_at) WHERE published_at IS NULL;

