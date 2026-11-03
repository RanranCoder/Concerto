CREATE TABLE agent_provider_config (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 org_id BIGINT NOT NULL,
 display_name VARCHAR(50) NOT NULL,
 protocol VARCHAR(40) NOT NULL,
 base_url VARCHAR(2048) NOT NULL,
 model_id VARCHAR(200) NOT NULL,
 api_key_ciphertext TEXT NOT NULL,
 summary_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 0,
 test_status VARCHAR(20) NOT NULL DEFAULT 'UNTESTED',
 tested_at TIMESTAMP NULL,
 test_summary BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uk_agent_provider_name UNIQUE(org_id, display_name)
);
CREATE TABLE agent_provider_default (
 org_id BIGINT PRIMARY KEY,
 config_id BIGINT NULL,
 version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE agent_provider_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 org_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 config_id BIGINT NULL,
 action VARCHAR(30) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE agent_provider_task_usage (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 stream_id VARCHAR(128) NOT NULL,
 org_id BIGINT NOT NULL,
 config_id BIGINT NULL,
 config_version BIGINT NULL,
 protocol VARCHAR(40) NOT NULL,
 model_id VARCHAR(200) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_provider_task_stream ON agent_provider_task_usage(org_id,stream_id);
