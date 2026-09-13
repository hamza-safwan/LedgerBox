CREATE TABLE user_accounts (
  id uuid PRIMARY KEY,
  email varchar(320) NOT NULL UNIQUE,
  password_hash varchar(255) NOT NULL,
  role varchar(32) NOT NULL CHECK (role IN ('CUSTOMER','OPERATOR')),
  enabled boolean NOT NULL DEFAULT false,
  failed_attempts integer NOT NULL DEFAULT 0,
  locked_until timestamptz,
  created_at timestamptz NOT NULL
);

CREATE TABLE action_tokens (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES user_accounts(id),
  token_hash char(64) NOT NULL UNIQUE,
  purpose varchar(32) NOT NULL CHECK (purpose IN ('VERIFY_EMAIL','RESET_PASSWORD')),
  expires_at timestamptz NOT NULL,
  used_at timestamptz
);

CREATE TABLE refresh_sessions (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES user_accounts(id),
  family_id uuid NOT NULL,
  token_hash char(64) NOT NULL UNIQUE,
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  replaced_by uuid,
  created_at timestamptz NOT NULL
);
CREATE INDEX refresh_sessions_family_idx ON refresh_sessions(family_id);

CREATE TABLE auth_audit (
  id bigserial PRIMARY KEY,
  user_id uuid,
  action varchar(80) NOT NULL,
  outcome varchar(32) NOT NULL,
  correlation_id varchar(100),
  occurred_at timestamptz NOT NULL DEFAULT now()
);

