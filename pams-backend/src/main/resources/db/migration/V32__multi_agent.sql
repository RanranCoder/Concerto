-- ===================== 多 Agent 协作基座（B 批次） =====================
-- 双库兼容（MySQL 8 / H2 MySQL 模式）：纯声明式 DDL，不用动态 SQL。
-- 迁移号：紧随 V31（阶段1 通用化）。
--
-- 本批次只建「协作骨架」：
--   agent_definition  Agent 注册表（全局目录，替代硬编码）
--   agent_run         一次协作（Run = <Goal, Plan, Blackboard, Steps[]>）
--   agent_step        协作步骤（可画协作拓扑）
-- 并在既有 agent_message / agent_task 上补 Agent 维度。
-- 默认 Agent 定义由代码初始化器（AgentDefinitionSeeder）幂等种入，避免在 SQL 里写 JSON 字面量。

-- ---------------------------------------------------------------------------
-- 1. agent_definition —— Agent 定义（全局目录，与组织无关）
--    tool_scope / role_prompt 用 TEXT 存 JSON/文本（由 JPA 写入，便于演进）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_definition (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(50) NOT NULL COMMENT '标识：coordinator/retrieval/planning/...',
  display_name VARCHAR(80) NOT NULL COMMENT '中文名',
  description VARCHAR(255) NULL,
  role_prompt TEXT NULL COMMENT '角色提示词；NULL 时用代码内置默认',
  tool_scope TEXT NULL COMMENT '工具作用域 JSON 数组；NULL=全部可见工具',
  model VARCHAR(50) NULL COMMENT '可覆盖的模型；NULL=全局默认',
  temperature DECIMAL(3,2) NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_agent_def_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多Agent-定义';

-- ---------------------------------------------------------------------------
-- 2. agent_run —— 一次协作（黑板书整体落库，支持协作过程回放）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_run (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id BIGINT NULL,
  user_id BIGINT NOT NULL,
  org_id BIGINT NOT NULL DEFAULT 1 COMMENT '租户组织ID',
  goal TEXT NULL COMMENT '本次协作目标',
  mode VARCHAR(10) NULL COMMENT 'P1串行/P2并行/P3反思/P4委派',
  status VARCHAR(20) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/FAILED',
  blackboard_json JSON NULL COMMENT '黑板（可回放）',
  started_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  finished_at DATETIME NULL,
  INDEX idx_agent_run_conv (conversation_id, id),
  INDEX idx_agent_run_user (user_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多Agent-协作Run';

-- ---------------------------------------------------------------------------
-- 3. agent_step —— 协作步骤（编排拓扑的数据源）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_step (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id BIGINT NOT NULL,
  agent_name VARCHAR(50) NOT NULL,
  seq INT NOT NULL DEFAULT 0 COMMENT 'Run 内单调递增',
  parent_step_id BIGINT NULL COMMENT '委派来源步骤',
  input_json JSON NULL,
  output_json JSON NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/FAILED/SKIPPED',
  duration_ms INT NULL,
  token_in INT NULL,
  token_out INT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_agent_step_run (run_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多Agent-协作步骤';

-- ---------------------------------------------------------------------------
-- 4. 既有表补 Agent 维度
-- ---------------------------------------------------------------------------
ALTER TABLE agent_message ADD COLUMN agent_name VARCHAR(50) NULL;
ALTER TABLE agent_task ADD COLUMN run_id BIGINT NULL;
ALTER TABLE agent_task ADD COLUMN agent_name VARCHAR(50) NULL;
