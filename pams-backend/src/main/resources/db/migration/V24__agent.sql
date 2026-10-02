-- ===================== 灵思（Agent）模块 基座（业务设计方案 D8/D9 + §3.3） =====================
-- 产品名「灵思」，代码标识 agent。本迁移为 B0 批次交付物。
--
-- 迁移号说明：V22 已被 drop_activity_session、V23 已被 signin_fields_json 占用，
-- 故 Agent 迁移使用 V24。沿用 CREATE TABLE IF NOT EXISTS + utf8mb4 约定。

-- ---------------------------------------------------------------------------
-- 1. agent_conversation 会话（按 user_id 强隔离，他人会话不可见）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_conversation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL COMMENT '归属用户，强隔离',
  title VARCHAR(120) NOT NULL DEFAULT '新对话' COMMENT '会话标题（首轮问答后取用户首条消息前18字）',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '用于列表排序与分组（今天/昨天/7天内/更早）',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删',
  INDEX idx_agent_conv_user (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='灵思-会话';

-- ---------------------------------------------------------------------------
-- 2. agent_message 消息（敏感内容仅存摘要，见 D4 / 决策6）
--    sensitivity: normal 存完整文本永久 / sensitive 仅摘要 90 天 / restricted 不落内容仅占位
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_message (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id BIGINT NOT NULL,
  role VARCHAR(20) NOT NULL COMMENT 'SYSTEM/USER/ASSISTANT/TOOL',
  content MEDIUMTEXT COMMENT '消息内容（敏感级仅存摘要）',
  tool_calls JSON NULL COMMENT '本轮工具调用摘要（不含结果内容）',
  sensitivity VARCHAR(20) NOT NULL DEFAULT 'normal' COMMENT 'normal/sensitive/restricted',
  stream_id VARCHAR(64) NULL COMMENT '关联的过程事件流ID，用于过程区回看',
  provider VARCHAR(20) NULL COMMENT '实际答复的 provider（deepseek/ollama/stub），用于前端"规则模式"标注',
  duration_ms INT NULL COMMENT '本轮总耗时',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_agent_msg_conv (conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='灵思-消息';

-- ---------------------------------------------------------------------------
-- 3. agent_task 任务 / 拟执行清单（D2 确认门禁，核心表）
--    status: PENDING_CONFIRM → RUNNING → SUCCESS/FAILED/PARTIAL/CANCELLED
--    乐观锁：UPDATE agent_task SET status='RUNNING' WHERE id=? AND status='PENDING_CONFIRM'
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_task (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL COMMENT '发起人',
  conversation_id BIGINT NULL,
  tool_name VARCHAR(80) NOT NULL COMMENT '写类工具名',
  task_type VARCHAR(10) NOT NULL DEFAULT 'SYNC' COMMENT 'SYNC/ASYNC',
  status VARCHAR(20) NOT NULL COMMENT 'PENDING_CONFIRM/RUNNING/SUCCESS/FAILED/PARTIAL/CANCELLED',
  summary VARCHAR(255) NULL COMMENT '拟执行清单摘要（如"第四十期培训班 · 素拓批量加分"）',
  plan_json JSON NULL COMMENT '拟执行清单：items/skipped/detailLines/reversible',
  result_json JSON NULL COMMENT '执行结果：成功数/失败数/明细',
  confirm_token VARCHAR(64) NULL COMMENT '一次性确认令牌，防重放',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  confirmed_at DATETIME NULL COMMENT '确认时间（审计用，见 D9）',
  finished_at DATETIME NULL,
  INDEX idx_agent_task_status (id, status),
  INDEX idx_agent_task_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='灵思-任务与拟执行清单';

-- ---------------------------------------------------------------------------
-- 4. agent_tool_call 调用流水（排障/统计 + 前端过程区历史回看的数据源）
--    不含结果内容，仅 tool_name/args_digest/duration/status —— 与审计同性质，永久保留
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_tool_call (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id BIGINT NULL,
  message_id BIGINT NULL,
  stream_id VARCHAR(64) NULL COMMENT '过程事件流ID，过程区按此回看',
  seq INT NOT NULL DEFAULT 0 COMMENT '流内序号，与 AgentStreamEvent.seq 对应',
  tool_name VARCHAR(80) NOT NULL,
  label VARCHAR(120) NULL COMMENT '中文业务名（前端展示用，避免前端映射表缺失）',
  args_json JSON NULL COMMENT '入参（已脱敏）',
  sensitivity VARCHAR(20) NOT NULL DEFAULT 'normal',
  side_effect VARCHAR(10) NOT NULL DEFAULT 'READ' COMMENT 'READ/WRITE',
  duration_ms INT NULL,
  result_status VARCHAR(20) NULL COMMENT 'OK/FAILED/DENIED/SKIPPED/TIMEOUT',
  error_message VARCHAR(500) NULL,
  user_id BIGINT NOT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_agent_toolcall_conv (conversation_id, id),
  INDEX idx_agent_toolcall_stream (stream_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='灵思-工具调用流水';

-- ---------------------------------------------------------------------------
-- 5. agent_audit_log 审计（D9，写操作 100% 覆盖；设计范式沿用 AttendanceAudit）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_audit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  conversation_id BIGINT NULL,
  task_id BIGINT NULL,
  tool_name VARCHAR(80) NOT NULL,
  args_digest VARCHAR(128) NULL COMMENT '入参摘要（hash），不落明文',
  side_effect VARCHAR(10) NOT NULL COMMENT 'READ/WRITE',
  confirmed_by BIGINT NULL COMMENT '确认人（写操作必填）',
  result_status VARCHAR(20) NULL,
  affected_count INT NULL COMMENT '影响条数',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_agent_audit_user (user_id, created_at),
  INDEX idx_agent_audit_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='灵思-审计日志';

-- ---------------------------------------------------------------------------
-- 6. 权限补录：notification:send
--    V7 只种了 notification:view / notification:preference，"定向发通知"能力此前
--    只存在于 Service（被 Controller 的角色宏掩盖）。灵思的 sendNotification 工具
--    需要以权限码声明（D3 第1层），故在此补录并授予领导角色。
--    这也顺带修正了一个既有缺口：通知发送此前无独立权限码可授。
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO permission (code, name, module, sort_order) VALUES
('notification:send', '发送通知', 'notification', 3);

-- 授予所有领导角色（与 V7 中领导角色集合一致）
INSERT IGNORE INTO role_permission (role, permission_id)
SELECT r.role, p.id FROM (
  SELECT 'TEACHER' AS role UNION ALL
  SELECT 'DIRECTOR' UNION ALL
  SELECT 'ORG_LEADER' UNION ALL
  SELECT 'SECRETARY_LEADER' UNION ALL
  SELECT 'MEDIA_LEADER' UNION ALL
  SELECT 'TECH_LEADER'
) r
JOIN permission p ON p.code = 'notification:send';
