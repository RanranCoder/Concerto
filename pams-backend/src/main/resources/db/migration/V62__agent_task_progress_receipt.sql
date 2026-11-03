CREATE TABLE agent_task_progress_operation (
 id VARCHAR(36) PRIMARY KEY, org_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 business_task_id BIGINT NOT NULL, baseline_revision BIGINT NOT NULL,
 before_progress INT, after_progress INT NOT NULL, result_revision BIGINT,
 created_at DATETIME(6) NOT NULL
);
