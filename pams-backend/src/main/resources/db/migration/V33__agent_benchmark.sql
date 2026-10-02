-- ===================== 多 Agent 对比评估实验（E 批次） =====================
-- 双库兼容（MySQL 8 / H2 MySQL 模式）：纯声明式 DDL。
-- 迁移号：紧随 V32。
--
-- 实验设计（论文实验章节数据源）：
--   自变量 = 协作方式：SINGLE（单个通才 Agent，可用全部工具）vs MULTI（多 Agent 分工协作）
--   因变量 = 完成率 / 平均步骤数 / 参与 Agent 数 / 工具调用数 / 审查通过率 / 耗时
-- 两条臂共用同一引擎（SpecialistAgent + ToolRegistry + LLM），唯一变量是「是否拆分协作」。

CREATE TABLE IF NOT EXISTS agent_benchmark (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  batch_id VARCHAR(64) NULL COMMENT '一次批量实验的标识',
  task_code VARCHAR(40) NOT NULL COMMENT '任务编码',
  task_text VARCHAR(255) NOT NULL COMMENT '任务描述',
  arm VARCHAR(20) NOT NULL COMMENT 'SINGLE 单Agent / MULTI 多Agent',
  mode VARCHAR(10) NULL COMMENT 'MULTI 的协作模式 P1–P4',
  run_id BIGINT NULL COMMENT '关联的 agent_run',
  status VARCHAR(20) NOT NULL COMMENT 'SUCCESS/FAILED',
  steps INT NOT NULL DEFAULT 0 COMMENT '步骤数',
  agents INT NOT NULL DEFAULT 0 COMMENT '参与的 Agent 数',
  tool_calls INT NOT NULL DEFAULT 0 COMMENT '成功工具调用数',
  artifacts INT NOT NULL DEFAULT 0 COMMENT '产物数',
  review_pass TINYINT NULL COMMENT '审查是否 PASS（MULTI 才有）',
  duration_ms INT NULL,
  org_id BIGINT NOT NULL DEFAULT 1,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_bench_batch (batch_id, arm),
  INDEX idx_bench_task (task_code, arm)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多Agent-对比实验';
