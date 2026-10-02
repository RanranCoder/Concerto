-- ===================== V26 的 H2 分支（测试 profile） =====================
-- MySQL 版 V26 用 information_schema 判存 + PREPARE 动态 DDL，H2 不支持。
-- 全新 H2 库结构确定（V24 建了 agent_task / agent_message），故直接加列即可，
-- 用 H2 原生 IF NOT EXISTS 保持幂等。效果与 MySQL 版一致。
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS stream_id VARCHAR(64) NULL;
CREATE INDEX IF NOT EXISTS idx_agent_task_stream ON agent_task (stream_id);
ALTER TABLE agent_message ADD COLUMN IF NOT EXISTS refs_json JSON NULL;
