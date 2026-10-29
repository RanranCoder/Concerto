CREATE TABLE notification_org_policy (
  org_id BIGINT PRIMARY KEY,
  quiet_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  quiet_start TIME NOT NULL,
  quiet_end TIME NOT NULL,
  digest_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  digest_time TIME NOT NULL,
  digest_since DATETIME(6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  updated_by BIGINT,
  updated_at DATETIME(6) NOT NULL
);
CREATE TABLE notification_policy_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  org_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  version BIGINT NOT NULL,
  quiet_enabled BOOLEAN NOT NULL,
  quiet_start TIME NOT NULL,
  quiet_end TIME NOT NULL,
  digest_enabled BOOLEAN NOT NULL,
  digest_time TIME NOT NULL,
  created_at DATETIME(6) NOT NULL,
  INDEX idx_notification_policy_audit(org_id,id)
);
CREATE TABLE notification_daily_digest (
  org_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  digest_day DATE NOT NULL,
  last_inbox_id BIGINT NOT NULL,
  item_count INT NOT NULL,
  message_id BIGINT,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY(org_id,user_id,digest_day)
);
CREATE TABLE notification_digest_item (
  org_id BIGINT NOT NULL,
  digest_message_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  source_message_id BIGINT NOT NULL,
  PRIMARY KEY(org_id,digest_message_id,user_id,source_message_id)
);
CREATE TABLE notification_metric (
  org_id BIGINT NOT NULL,
  metric_key VARCHAR(60) NOT NULL,
  total_value BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(org_id,metric_key)
);
ALTER TABLE notification_outbox ADD COLUMN completed_at DATETIME(6);
ALTER TABLE notification_inbox ADD COLUMN silent_at_materialization BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE notification_outbox ADD COLUMN recipient_count INT;
ALTER TABLE notification_outbox ADD COLUMN skipped_count INT;
ALTER TABLE notification_delivery ADD COLUMN completed_at DATETIME(6);
CREATE INDEX idx_notification_outbox_completed ON notification_outbox(org_id,completed_at);
CREATE INDEX idx_notification_delivery_completed ON notification_delivery(org_id,completed_at);
