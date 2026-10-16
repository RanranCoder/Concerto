-- ---------------------------------------------------------------------------
-- V35 思维链落库（灵思过程区「历史回放」的数据源）
--
-- thinking_json: 本轮模型推理（reasoning_content）按 LLM 调用轮次分段：
--   [{round, text, startedAt, endedAt, durationMs}]
--
-- 为什么必须落库：模型推理是流式片段（实时走 THINKING_DELTA 事件），
-- 而事件流不持久化 —— 不落库则刷新页面后思维链即消失，
-- 只剩工具行（tool_calls 派生），用户看到的时间轴与刚才读到的不是同一条。
--
-- 脱敏：thinking_json 仅在 sensitivity = normal 时写入；
--       敏感级只存摘要、受限级不落内容，思考原文同理（写时脱敏，见 D4 / 决策 6）。
-- ---------------------------------------------------------------------------
ALTER TABLE agent_message
  ADD COLUMN thinking_json JSON NULL COMMENT '本轮思维链（按 round 分段的模型推理原文；敏感级不落）';
