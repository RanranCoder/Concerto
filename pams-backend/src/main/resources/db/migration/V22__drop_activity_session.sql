-- ===================== 活动级单码重构（V22） =====================
-- 移除“签到场次(activity_session)”概念：改为“一个活动一个签到码 + 浏览器时间判时”。
-- 表本无外键引用（session_id 列仅索引，无 FK），可直接 DROP。
DROP TABLE IF EXISTS activity_session;