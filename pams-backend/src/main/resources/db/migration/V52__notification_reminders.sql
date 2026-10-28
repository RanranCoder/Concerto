CREATE TABLE notification_reminder_source (
  org_id BIGINT NOT NULL,
  resource_type VARCHAR(24) NOT NULL,
  resource_id BIGINT NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  generation BIGINT NOT NULL DEFAULT 0,
  active BOOLEAN NOT NULL DEFAULT FALSE,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(org_id,resource_type,resource_id)
);
CREATE TABLE notification_reminder (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  org_id BIGINT NOT NULL,
  resource_type VARCHAR(24) NOT NULL,
  resource_id BIGINT NOT NULL,
  generation BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  rule_key VARCHAR(40) NOT NULL,
  window_key VARCHAR(40) NOT NULL,
  due_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6),
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NOT NULL,
  message_id BIGINT,
  last_error VARCHAR(120),
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE(org_id,resource_type,resource_id,user_id,rule_key,window_key)
);
CREATE INDEX idx_notification_reminder_due ON notification_reminder(org_id,status,due_at,id);
