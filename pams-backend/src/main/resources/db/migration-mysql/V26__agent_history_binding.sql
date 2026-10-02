-- ===================== 灵思历史恢复：任务绑流 + 引用落库（V26） =====================
-- 背景（2026-09-14 历史持久化修复）：
--   1) agent_task 只有 conversation_id，没有 stream_id —— 重新进入会话时
--      确认卡/表单卡无法绑定到产生它的那条助手消息，前端只能整体丢弃；
--      且新会话首轮 ctx.conversationId 为 null（会话在编排器内部才创建），
--      首轮任务连 conversation_id 都落了 NULL。stream_id 是消息与任务之间的
--      天然关联键（agent_message.stream_id 已存在，V24）。
--   2) 工具结果引用（references，如"可跳转的实体链接"）只随 /chat 同步返回，
--      从不落库 —— 刷新即丢。落在 assistant 消息行上（refs_json）。
-- 幂等策略与 V25 相同：information_schema 判存后加列，全新库跳过。

SET @task_col = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME  = 'agent_task'
    AND COLUMN_NAME = 'stream_id'
);

SET @ddl = IF(@task_col = 0,
  'ALTER TABLE agent_task ADD COLUMN stream_id VARCHAR(64) NULL COMMENT ''产生本任务的过程流ID（绑定消息用）'' AFTER conversation_id, ADD INDEX idx_agent_task_stream (stream_id)',
  'SELECT ''agent_task.stream_id 已存在，跳过'' AS note'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @msg_col = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME  = 'agent_message'
    AND COLUMN_NAME = 'refs_json'
);

SET @ddl = IF(@msg_col = 0,
  'ALTER TABLE agent_message ADD COLUMN refs_json JSON NULL COMMENT ''本轮工具结果引用（实体跳转链接），随消息持久化'' AFTER tool_calls',
  'SELECT ''agent_message.refs_json 已存在，跳过'' AS note'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
