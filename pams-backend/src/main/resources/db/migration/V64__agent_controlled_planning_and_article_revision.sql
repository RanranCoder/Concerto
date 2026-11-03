ALTER TABLE article ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE schedule ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_work_item ADD COLUMN contract_json JSON NULL;
ALTER TABLE agent_work_item ADD COLUMN result_json JSON NULL;
CREATE TABLE agent_controlled_plan (
 id VARCHAR(36) PRIMARY KEY,
 org_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 conversation_id BIGINT NULL,
 tool_mode VARCHAR(20) NOT NULL,
 goal TEXT NOT NULL,
 payload_json LONGTEXT NOT NULL,
 expires_at DATETIME NOT NULL,
 run_id BIGINT NULL,
 created_at DATETIME NOT NULL
);
CREATE TABLE agent_knowledge_evidence (
 id VARCHAR(36) PRIMARY KEY,
 org_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 conversation_id BIGINT NULL,
 chunk_id BIGINT NOT NULL,
 content_sha256 VARCHAR(64) NOT NULL,
 expires_at DATETIME NOT NULL
);
