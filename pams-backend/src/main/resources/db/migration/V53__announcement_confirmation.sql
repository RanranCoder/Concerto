ALTER TABLE announcement ADD COLUMN important BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE announcement ADD COLUMN require_confirmation BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE announcement ADD COLUMN confirmation_version INT NOT NULL DEFAULT 1;
CREATE TABLE announcement_receipt (
  org_id BIGINT NOT NULL,
  announcement_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  version INT NOT NULL,
  confirmed_at DATETIME NULL,
  PRIMARY KEY(org_id,announcement_id,user_id,version),
  INDEX idx_announcement_receipt_user(org_id,user_id,confirmed_at)
);
