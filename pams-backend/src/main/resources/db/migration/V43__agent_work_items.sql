-- Work items are dependency nodes; agent_task remains the confirmation gate.
ALTER TABLE agent_run ADD COLUMN stream_id VARCHAR(80);
ALTER TABLE agent_run ADD COLUMN tool_mode VARCHAR(10) DEFAULT 'standard' NOT NULL;
ALTER TABLE agent_run ADD COLUMN authority_json JSON;
ALTER TABLE agent_run ADD COLUMN dispatch_token VARCHAR(36);
ALTER TABLE agent_run ADD COLUMN dispatch_started_at DATETIME;
CREATE TABLE agent_work_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    node_key INT NOT NULL,
    agent_name VARCHAR(60) NOT NULL,
    instruction TEXT NOT NULL,
    dependencies_json JSON NOT NULL,
    status VARCHAR(24) NOT NULL,
    revision INT NOT NULL DEFAULT 1,
    attempt INT NOT NULL DEFAULT 0,
    output_text TEXT,
    action_task_id BIGINT,
    updated_at DATETIME NOT NULL,
    UNIQUE (run_id, node_key)
);
CREATE INDEX idx_work_item_run_status ON agent_work_item(run_id, status);
ALTER TABLE agent_task ADD COLUMN work_item_id BIGINT;
ALTER TABLE agent_task ADD COLUMN work_item_revision INT;
CREATE INDEX idx_agent_task_work_item ON agent_task(work_item_id, work_item_revision);
ALTER TABLE agent_step ADD COLUMN work_item_id BIGINT;
ALTER TABLE agent_step ADD COLUMN attempt INT;
ALTER TABLE agent_step ADD COLUMN revision INT;
ALTER TABLE agent_task ADD COLUMN row_version BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE agent_task ADD COLUMN data_sensitivity VARCHAR(12) DEFAULT 'NORMAL' NOT NULL;
ALTER TABLE agent_message ADD COLUMN run_id BIGINT;
