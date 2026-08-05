-- ===================== F06 群聊管理 =====================
CREATE TABLE IF NOT EXISTS group_chat (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(50) NOT NULL COMMENT '群聊名称',
  category_id BIGINT COMMENT '分类ID',
  activity_id BIGINT COMMENT '关联活动ID',
  owner_id BIGINT COMMENT '群主/负责人用户ID',
  qr_code_url VARCHAR(500) COMMENT '群二维码图片URL',
  remark VARCHAR(200),
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE活跃/DISSOLVED解散/ARCHIVED归档',
  created_by BIGINT COMMENT '创建人用户ID',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME,
  deleted TINYINT DEFAULT 0,
  INDEX idx_group_chat_category (category_id),
  INDEX idx_group_chat_activity (activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群聊';

CREATE TABLE IF NOT EXISTS group_chat_category (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(50) NOT NULL COMMENT '分类名称',
  sort_order INT DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群聊分类';

CREATE TABLE IF NOT EXISTS group_chat_department (
  group_chat_id BIGINT NOT NULL COMMENT '群聊ID',
  department VARCHAR(50) NOT NULL COMMENT '部门名',
  PRIMARY KEY (group_chat_id, department)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群聊-部门关联';
