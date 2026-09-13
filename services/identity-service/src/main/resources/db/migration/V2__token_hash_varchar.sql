ALTER TABLE action_tokens
  ALTER COLUMN token_hash TYPE varchar(64);

ALTER TABLE refresh_sessions
  ALTER COLUMN token_hash TYPE varchar(64);
