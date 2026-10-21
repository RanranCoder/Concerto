CREATE TABLE member_account_batch (
  id VARCHAR(64) PRIMARY KEY,
  org_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  authz_version BIGINT NOT NULL,
  request_json TEXT NOT NULL,
  preview_json LONGTEXT NOT NULL,
  status VARCHAR(20) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  applied_at TIMESTAMP NULL,
  INDEX idx_account_batch_actor (org_id, actor_id, created_at)
);

CREATE TABLE member_account_credential (
  user_id BIGINT PRIMARY KEY,
  batch_id VARCHAR(64) NOT NULL,
  org_id BIGINT NOT NULL,
  pending_password_hash VARCHAR(100) NOT NULL,
  token_hash VARCHAR(64) NULL UNIQUE,
  expires_at TIMESTAMP NULL,
  consumed_at TIMESTAMP NULL,
  INDEX idx_account_credential_batch (batch_id)
);
