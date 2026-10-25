CREATE TABLE agent_artifact (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    org_id BIGINT NOT NULL,
    conversation_id BIGINT,
    run_id BIGINT,
    work_item_id BIGINT,
    title VARCHAR(255),
    tool_name VARCHAR(80) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    current_version INT DEFAULT 0 NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE (work_item_id)
);
CREATE INDEX idx_agent_artifact_conversation ON agent_artifact(conversation_id,id);
CREATE INDEX idx_agent_artifact_run ON agent_artifact(run_id,id);
CREATE TABLE agent_artifact_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id BIGINT NOT NULL,
    version INT NOT NULL,
    task_id BIGINT NOT NULL,
    work_item_revision INT,
    attempt INT,
    origin VARCHAR(16) NOT NULL,
    preview MEDIUMTEXT,
    fields_json JSON,
    plan_json JSON,
    review_status VARCHAR(24) NOT NULL,
    sources_json JSON,
    created_at DATETIME NOT NULL,
    UNIQUE (artifact_id,version),
    UNIQUE (task_id)
);
ALTER TABLE agent_task ADD COLUMN artifact_id BIGINT;
ALTER TABLE agent_task ADD COLUMN artifact_version INT;
