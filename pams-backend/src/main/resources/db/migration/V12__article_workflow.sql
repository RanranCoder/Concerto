-- ===================== 活动内推文管理（对接秀米+公众号发布流程） =====================
ALTER TABLE article ADD image_urls TEXT NULL COMMENT '长图截图 URL 列表（JSON 数组字符串）';
ALTER TABLE article ADD deadline DATETIME NULL COMMENT '任务截止时间';
ALTER TABLE article ADD wx_url VARCHAR(500) NULL COMMENT '公众号发布链接';
ALTER TABLE article ADD read_count INT NOT NULL DEFAULT 0 COMMENT '阅读量';
ALTER TABLE article ADD like_count INT NOT NULL DEFAULT 0 COMMENT '在看数';
ALTER TABLE article ADD deadline_reminded_at DATETIME NULL COMMENT '截止提醒去重（最近一次提醒时间）';
CREATE INDEX idx_article_activity ON article(activity_id);
