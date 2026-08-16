CREATE TABLE IF NOT EXISTS member_session (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(50) NOT NULL UNIQUE COMMENT '届名，如"第九届"',
  is_current TINYINT DEFAULT 0 COMMENT '是否当前届',
  sort_order INT DEFAULT 0,
  remark VARCHAR(255),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS member (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL COMMENT '所属届别',
  dept_id BIGINT COMMENT '部门，主任/副主任为空',
  position VARCHAR(20) NOT NULL COMMENT 'DIRECTOR/SUB_DIRECTOR/DEPT_HEAD/SUB_DEPT_HEAD/STAFF',
  name VARCHAR(50) NOT NULL COMMENT '姓名',
  gender VARCHAR(2) COMMENT '男/女',
  student_no VARCHAR(30) COMMENT '学号',
  class_name VARCHAR(100) COMMENT '班级',
  phone VARCHAR(20) COMMENT '联系方式',
  political_status VARCHAR(20) COMMENT '政治面貌(中文)',
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ALUMNI/RESIGNED/EXPELLED/LEFT',
  remark VARCHAR(255),
  created_by BIGINT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  CONSTRAINT fk_member_session FOREIGN KEY (session_id) REFERENCES member_session(id),
  CONSTRAINT fk_member_dept FOREIGN KEY (dept_id) REFERENCES sys_department(id),
  UNIQUE KEY uk_member_session_student (session_id, student_no)
);
