ALTER TABLE notification_incident MODIFY COLUMN first_seen_at DATETIME(6) NOT NULL;
ALTER TABLE notification_incident MODIFY COLUMN last_seen_at DATETIME(6) NOT NULL;
CREATE TABLE notification_maintenance_subscription (
  org_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  enabled_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(org_id,user_id)
);
CREATE TABLE notification_incident_delivery (
  org_id BIGINT NOT NULL,
  incident_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  message_id BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY(org_id,incident_id,user_id)
);
