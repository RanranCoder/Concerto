-- ---------------------------------------------------------------------------
-- V36 agent_message 思维链两列的幂等补正 + 编排旁白列
--
-- 背景：
--   1) V35 是裸 `ALTER TABLE ... ADD COLUMN thinking_json`，不幂等；
--      仓库范式是 information_schema 判存 + PREPARE 动态 DDL（见 V25/V26/V27）。
--      V35 可能已在别的机器上执行过，所以**不改历史迁移**（改已应用的迁移会让
--      Flyway 校验和不匹配），而是在这里补一次判存加列。
--   2) 新增 thinking_notes_json：编排旁白（THINKING 事件，「正在理解你的问题…」
--      「已找到 N 条相关依据，正在整理…」）此前只发事件、不落库，
--      刷新后过程区少了这些行 —— 与"刷新前后看到同一条时间轴"的目标不符。
--      旁白与模型推理语义不同，故分列存储（见 AgentMessage.thinkingNotesJson 注释）。
--
-- 注意（运维）：若某台机器的 V35 **执行失败**过，Flyway 会留下 failed 记录并拒绝继续迁移，
--   这种库需要先 `flyway repair`（或手工清 flyway_schema_history 里 V35 的 failed 行）
--   才能让 V36 生效；V36 只能覆盖「V35 成功」与「V35 从未执行」这两种情况。
-- ---------------------------------------------------------------------------

-- 1) 模型推理分段（等价于 V35，判存后加）
SET @col_thinking = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME  = 'agent_message'
    AND COLUMN_NAME = 'thinking_json'
);

SET @ddl = IF(@col_thinking = 0,
  'ALTER TABLE agent_message ADD COLUMN thinking_json JSON NULL COMMENT ''本轮思维链（按 LLM 轮次分段的模型推理原文；敏感级不落）''',
  'SELECT ''agent_message.thinking_json 已存在，跳过'' AS note'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 编排旁白（V36 新增）
SET @col_notes = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME  = 'agent_message'
    AND COLUMN_NAME = 'thinking_notes_json'
);

SET @ddl = IF(@col_notes = 0,
  'ALTER TABLE agent_message ADD COLUMN thinking_notes_json JSON NULL COMMENT ''本轮编排旁白 [{note,ts,durationMs?}]（敏感级不落）'' AFTER thinking_json',
  'SELECT ''agent_message.thinking_notes_json 已存在，跳过'' AS note'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
