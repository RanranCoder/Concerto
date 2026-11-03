CREATE TABLE agent_business_operation (
 id VARCHAR(36) PRIMARY KEY, org_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 kind VARCHAR(60) NOT NULL, payload_json LONGTEXT NOT NULL, result_json LONGTEXT,
 expires_at DATETIME(6) NOT NULL, created_at DATETIME(6) NOT NULL
);
CREATE TABLE agent_notification_audience (
 id VARCHAR(36) PRIMARY KEY, org_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 selector_json TEXT NOT NULL, recipients_json TEXT NOT NULL,
 expires_at DATETIME(6) NOT NULL
);
