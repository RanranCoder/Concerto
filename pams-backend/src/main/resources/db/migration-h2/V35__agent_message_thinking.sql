-- ---------------------------------------------------------------------------
-- V35 思维链落库（H2 分支：语法与 MySQL 一致，但 H2 不支持列内联 COMMENT）
-- 结构与原因见 db/migration-mysql/V35__agent_message_thinking.sql
-- ---------------------------------------------------------------------------
ALTER TABLE agent_message
  ADD COLUMN thinking_json JSON NULL;
