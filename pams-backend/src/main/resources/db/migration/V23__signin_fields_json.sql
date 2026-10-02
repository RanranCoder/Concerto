-- ===================== 签到自定义表单字段值回显（V23） =====================
-- 扫码提交的自定义表单字段值落库（JSON），供签到记录列表回显。
ALTER TABLE signin ADD COLUMN fields_json TEXT COMMENT '扫码提交的表单字段值（JSON：字段名->值）';