CREATE TABLE agent_memory_setting (
 org_id BIGINT NOT NULL, user_id BIGINT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 PRIMARY KEY(org_id,user_id)
);
CREATE TABLE agent_long_term_memory (
 id VARCHAR(36) PRIMARY KEY, org_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 scope_key VARCHAR(100) NOT NULL, memory_key VARCHAR(80) NOT NULL, content VARCHAR(1000),
 status VARCHAR(16) NOT NULL, revision BIGINT NOT NULL DEFAULT 1,
 source_conversation_id BIGINT, source_message_id BIGINT, source_hash VARCHAR(64),
 expires_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE(org_id,user_id,scope_key,memory_key)
);
CREATE INDEX idx_agent_memory_owner ON agent_long_term_memory(org_id,user_id,status);
