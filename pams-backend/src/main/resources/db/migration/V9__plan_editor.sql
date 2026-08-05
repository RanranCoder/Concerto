-- ===================== 策划书编辑器增强（wangEditor 5） =====================
-- 新增可覆盖字段 + 章节顺序 JSON（批次D，每列独立 ALTER 以兼容 H2 MySQL 模式）
ALTER TABLE activity_plan ADD name_override TEXT NULL COMMENT '活动名称可覆盖值';
ALTER TABLE activity_plan ADD theme_override TEXT NULL COMMENT '活动主题可覆盖值';
ALTER TABLE activity_plan ADD time_override TEXT NULL COMMENT '活动时间可覆盖值（YYYY-MM-DD|时间段）';
ALTER TABLE activity_plan ADD location_override TEXT NULL COMMENT '活动地点可覆盖值';
ALTER TABLE activity_plan ADD organizer_override TEXT NULL COMMENT '组织单位可覆盖值';
ALTER TABLE activity_plan ADD target_override TEXT NULL COMMENT '活动对象可覆盖值';
ALTER TABLE activity_plan ADD section_order TEXT NULL COMMENT '章节顺序+自定义节名 JSON，NULL=默认模板';
