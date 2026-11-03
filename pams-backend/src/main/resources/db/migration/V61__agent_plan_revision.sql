CREATE TABLE agent_plan_snapshot (
 id VARCHAR(64) PRIMARY KEY, org_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 plan_id BIGINT NOT NULL, conversation_id BIGINT, baseline VARCHAR(64) NOT NULL,
 source_base64 LONGTEXT NOT NULL, blocks_json LONGTEXT NOT NULL,
 covered_through INT NOT NULL DEFAULT 0, total_blocks INT NOT NULL,
 eligible BOOLEAN NOT NULL, expires_at DATETIME(6) NOT NULL
);
CREATE INDEX idx_agent_snapshot_expiry ON agent_plan_snapshot(expires_at);
CREATE TABLE agent_plan_revision (
 id VARCHAR(36) PRIMARY KEY, snapshot_id VARCHAR(64) NOT NULL,
 org_id BIGINT NOT NULL, user_id BIGINT NOT NULL, plan_id BIGINT NOT NULL,
 source_message_id BIGINT NOT NULL, before_text TEXT NOT NULL, after_text TEXT NOT NULL,
 block_id VARCHAR(80) NOT NULL, result_plan_id BIGINT,
 created_at DATETIME(6) NOT NULL
);
