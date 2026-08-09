-- ===================== F01 座位表可视化布局 =====================
CREATE TABLE IF NOT EXISTS seat_layout (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT COMMENT '所属活动（模板为 NULL）',
  name VARCHAR(100) NOT NULL COMMENT '布局名称',
  `rows` INT NOT NULL COMMENT '行数',
  `cols` INT NOT NULL COMMENT '列数',
  aisle_cols VARCHAR(255) COMMENT '过道列索引，逗号分隔，如 5,12',
  aisle_width_ratio DECIMAL(3,1) DEFAULT 1.5 COMMENT '过道宽度倍率（1.5-2.0）',
  seat_data TEXT COMMENT '格子 JSON：[{row,col,type,color,label,state}]',
  color_labels TEXT COMMENT '自定义配色 JSON：[{color,label}]',
  is_template TINYINT DEFAULT 0 COMMENT '是否模板',
  template_category VARCHAR(50) COMMENT '模板分类',
  created_by BIGINT COMMENT '创建人用户ID',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME,
  INDEX idx_seat_layout_activity (activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='座位表布局';

-- 预设模板种子（大教室/报告厅/会议室/多功能厅，幂等：按名称防重复）
INSERT INTO seat_layout (name, `rows`, `cols`, aisle_cols, aisle_width_ratio, is_template, template_category, created_at, updated_at)
SELECT '标准大教室', 12, 10, '5', 1.5, 1, 'CLASSROOM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM seat_layout WHERE name = '标准大教室');

INSERT INTO seat_layout (name, `rows`, `cols`, aisle_cols, aisle_width_ratio, is_template, template_category, created_at, updated_at)
SELECT '报告厅', 20, 24, '11,14', 1.5, 1, 'HALL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM seat_layout WHERE name = '报告厅');

INSERT INTO seat_layout (name, `rows`, `cols`, aisle_cols, aisle_width_ratio, is_template, template_category, created_at, updated_at)
SELECT '会议室', 8, 12, '6', 1.5, 1, 'MEETINGROOM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM seat_layout WHERE name = '会议室');

INSERT INTO seat_layout (name, `rows`, `cols`, aisle_cols, aisle_width_ratio, is_template, template_category, created_at, updated_at)
SELECT '多功能厅', 16, 18, '8,11', 1.5, 1, 'MULTIFUNCTION', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM seat_layout WHERE name = '多功能厅');
