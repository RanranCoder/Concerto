-- ---------------------------------------------------------------------------
-- V36 的 H2 分支（测试 profile）
-- MySQL 版用 information_schema + PREPARE 动态 DDL，H2 不支持该语法；
-- H2 用原生 IF NOT EXISTS 达到同样的幂等效果（与 V25/V26 的 H2 分支同一约定）。
--
-- 内容与 MySQL 版一致：① 补 V35 的 thinking_json（幂等）② 新增 thinking_notes_json。
-- 详见 db/migration-mysql/V36__agent_message_thinking_notes.sql 的说明。
-- ---------------------------------------------------------------------------
ALTER TABLE agent_message ADD COLUMN IF NOT EXISTS thinking_json JSON NULL;
ALTER TABLE agent_message ADD COLUMN IF NOT EXISTS thinking_notes_json JSON NULL;
