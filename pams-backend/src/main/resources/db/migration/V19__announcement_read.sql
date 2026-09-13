-- ===================== 公告已读后端化（PRD-v5 F4） =====================
-- 已读状态原仅存前端 localStorage（换设备/清缓存即失效），落库后跨端一致。
CREATE TABLE IF NOT EXISTS announcement_read (
  announcement_id BIGINT NOT NULL COMMENT '公告ID',
  user_id BIGINT NOT NULL COMMENT '已读用户ID',
  read_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '已读时间',
  PRIMARY KEY (announcement_id, user_id),
  CONSTRAINT fk_ar_announcement FOREIGN KEY (announcement_id) REFERENCES announcement(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公告已读记录';
