-- Keep notification IDs/API references stable: notification is the message fact table.
ALTER TABLE notification ADD COLUMN event_key VARCHAR(100);
ALTER TABLE notification ADD COLUMN activity_id BIGINT;
CREATE UNIQUE INDEX uk_notification_event ON notification(org_id,event_key);

CREATE TABLE notification_inbox (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  org_id BIGINT NOT NULL,
  message_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  read_at DATETIME(6),
  archived_at DATETIME(6),
  created_at DATETIME(6) NOT NULL,
  UNIQUE(org_id,message_id,user_id)
);
CREATE INDEX idx_inbox_user ON notification_inbox(org_id,user_id,read_at,created_at,id);
CREATE TABLE notification_outbox (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  org_id BIGINT NOT NULL,
  message_id BIGINT NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NOT NULL,
  lease_token VARCHAR(40),
  lease_until DATETIME(6),
  last_error VARCHAR(300),
  created_at DATETIME(6) NOT NULL,
  UNIQUE(org_id,message_id)
);
CREATE INDEX idx_notification_outbox_due ON notification_outbox(status,next_attempt_at,lease_until);
CREATE TABLE notification_outbox_recipient (
  outbox_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  PRIMARY KEY(outbox_id,user_id)
);
CREATE TABLE notification_delivery (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  org_id BIGINT NOT NULL,
  message_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NOT NULL,
  lease_token VARCHAR(40),
  lease_until DATETIME(6),
  last_error VARCHAR(300),
  created_at DATETIME(6) NOT NULL,
  UNIQUE(org_id,message_id,user_id)
);
CREATE INDEX idx_notification_delivery_due ON notification_delivery(status,next_attempt_at,lease_until);
CREATE TABLE notification_preference (
  org_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  enabled BOOLEAN NOT NULL,
  PRIMARY KEY(org_id,user_id,event_type)
);
-- Historical personal rows retain their read status. Shared rows become archived
-- history for the migration-time audience: their true per-user read history is unknown.
INSERT INTO notification_inbox(org_id,message_id,user_id,read_at,archived_at,created_at)
SELECT n.org_id,n.id,u.id,
  CASE WHEN n.is_read=1 THEN COALESCE(n.read_at,n.created_at) ELSE NULL END,
  CASE WHEN n.recipient_id IS NULL THEN CURRENT_TIMESTAMP ELSE NULL END,
  COALESCE(n.created_at,CURRENT_TIMESTAMP)
FROM notification n JOIN sys_user u ON u.org_id=n.org_id AND u.deleted=0
WHERE n.deleted=0 AND (u.id=n.recipient_id OR (n.recipient_id IS NULL AND
  (u.dept_id=n.recipient_dept_id OR EXISTS
    (SELECT 1 FROM sys_role r WHERE r.id=u.role_id AND r.code=n.recipient_role))));
