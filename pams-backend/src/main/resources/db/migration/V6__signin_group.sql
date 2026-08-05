-- ===================== B1/B2 签到名单分组 + 素拓活动加分 =====================

-- B1: 名单分组表
CREATE TABLE IF NOT EXISTS sign_in_group (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL COMMENT '所属活动',
  group_name VARCHAR(100) NOT NULL COMMENT '分组名（默认=文件名去扩展名）',
  source_filename VARCHAR(255) COMMENT '来源名单文件名',
  sort_order INT DEFAULT 0 COMMENT '排序',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_group_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='签到名单分组';

-- 名单行归属分组
ALTER TABLE signin_roster ADD group_id BIGINT NULL COMMENT '所属分组ID';

-- 旧名单回填默认分组：为每个有名单行的活动创建「未分组」，并把该活动未归组的行归入
INSERT INTO sign_in_group (activity_id, group_name, source_filename, sort_order, created_at)
SELECT DISTINCT r.activity_id, '未分组', NULL, 1, NOW()
FROM signin_roster r;

UPDATE signin_roster rr
SET rr.group_id = (SELECT g.id FROM sign_in_group g WHERE g.activity_id = rr.activity_id)
WHERE rr.group_id IS NULL;

ALTER TABLE signin_roster ADD CONSTRAINT fk_roster_group FOREIGN KEY (group_id) REFERENCES sign_in_group(id);

-- B2: 素拓活动加分来源追溯（批量加分整体撤回）
ALTER TABLE credit_record ADD source_activity_id BIGINT NULL COMMENT '来源活动ID';
ALTER TABLE credit_record ADD batch_id VARCHAR(36) NULL COMMENT '批量加分批次ID';
