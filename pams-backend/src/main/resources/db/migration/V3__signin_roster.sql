CREATE TABLE IF NOT EXISTS signin_roster (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  fields_json TEXT NOT NULL COMMENT '核验字段值 JSON，键=字段名，值=该人对应值',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_roster_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS signin_field_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  field_name VARCHAR(50) NOT NULL COMMENT '字段显示名，如 姓名/学号/手机号',
  field_key VARCHAR(50) NOT NULL COMMENT '字段键，如 name/studentNo/phone',
  required TINYINT DEFAULT 0,
  field_type VARCHAR(20) DEFAULT 'TEXT' COMMENT 'TEXT/NUMBER/PHONE',
  sort_order INT DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_field_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);
