-- V37 already provisioned this column; the task entity now maps it.
-- Do not infer account identity from historical names, which may be duplicated.
CREATE INDEX idx_task_assignee_account ON task(org_id,assignee_user_id,end_date);
