CREATE TABLE notification_operation_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  org_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  pipeline_kind VARCHAR(20) NOT NULL,
  record_id BIGINT NOT NULL,
  previous_attempts INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_notification_operation_audit(org_id,id)
);
CREATE TABLE notification_incident (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  org_id BIGINT NOT NULL,
  category VARCHAR(40) NOT NULL,
  window_date DATE NOT NULL,
  occurrences INT NOT NULL DEFAULT 1,
  first_seen_at DATETIME NOT NULL,
  last_seen_at DATETIME NOT NULL,
  UNIQUE KEY uk_notification_incident(org_id,category,window_date)
);
