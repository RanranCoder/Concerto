ALTER TABLE agent_run ADD COLUMN event_seq BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE agent_run ADD COLUMN cancel_requested BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE agent_run ADD COLUMN request_id VARCHAR(64);
ALTER TABLE agent_run ADD COLUMN dispatch_owner VARCHAR(36);
ALTER TABLE agent_task ADD COLUMN execution_owner VARCHAR(36);
ALTER TABLE agent_work_item ADD COLUMN review_status VARCHAR(24) DEFAULT 'NOT_REVIEWED' NOT NULL;
CREATE TABLE agent_request (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    org_id BIGINT NOT NULL,
    stream_id VARCHAR(80),
    conversation_id BIGINT,
    run_id BIGINT,
    authz_version BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    cancelled BOOLEAN DEFAULT FALSE NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE (stream_id)
);
CREATE INDEX idx_agent_request_owner ON agent_request(user_id,updated_at);
CREATE TABLE agent_run_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    org_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    event_seq BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    wake_required BOOLEAN DEFAULT FALSE NOT NULL,
    created_at DATETIME NOT NULL,
    delivered_at DATETIME,
    lease_until DATETIME,
    delivery_token VARCHAR(36),
    attempts INT DEFAULT 0 NOT NULL,
    UNIQUE (run_id,event_seq)
);
CREATE INDEX idx_agent_event_delivery ON agent_run_event(delivered_at,lease_until,id);
CREATE TABLE agent_review_issue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    work_item_id BIGINT NOT NULL,
    revision INT NOT NULL,
    attempt INT NOT NULL,
    severity VARCHAR(16) NOT NULL,
    code VARCHAR(48) NOT NULL,
    location VARCHAR(100) NOT NULL,
    quote_text TEXT,
    reason TEXT NOT NULL,
    source_ref VARCHAR(200),
    created_at DATETIME NOT NULL
);
CREATE INDEX idx_agent_review_node ON agent_review_issue(work_item_id,revision,attempt);

ALTER TABLE kb_retrieval_log ADD COLUMN input_fragments_json JSON;
