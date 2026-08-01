-- ===================== 一、用户与组织 =====================
CREATE TABLE IF NOT EXISTS sys_department (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(50) NOT NULL UNIQUE COMMENT '部门名',
  sort_order INT DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_role (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(30) NOT NULL UNIQUE COMMENT 'TEACHER/DIRECTOR/ORG_LEADER/SECRETARY_LEADER/MEDIA_LEADER/TECH_LEADER/STAFF',
  name VARCHAR(30) NOT NULL,
  level INT NOT NULL DEFAULT 0 COMMENT '5指导老师 4主任 3部长 1干事',
  data_scope VARCHAR(20) NOT NULL DEFAULT 'ALL' COMMENT 'ALL/DEPT',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(50) NOT NULL UNIQUE,
  password VARCHAR(100) NOT NULL,
  real_name VARCHAR(50) NOT NULL,
  student_no VARCHAR(20),
  phone VARCHAR(20),
  dept_id BIGINT,
  role_id BIGINT NOT NULL,
  status TINYINT DEFAULT 1 COMMENT '1启用 0禁用',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  CONSTRAINT fk_user_dept FOREIGN KEY (dept_id) REFERENCES sys_department(id),
  CONSTRAINT fk_user_role FOREIGN KEY (role_id) REFERENCES sys_role(id)
);

-- ===================== 二、活动管理 =====================
CREATE TABLE IF NOT EXISTS activity (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  theme VARCHAR(200),
  type VARCHAR(20) NOT NULL DEFAULT 'OTHER' COMMENT 'PARTY_LESSON/DATE/PARTY_DAY/COMPETITION/VOLUNTEER/LECTURE/MEETING/OTHER',
  status VARCHAR(20) NOT NULL DEFAULT 'ASSIGNED'
    COMMENT 'ASSIGNED/PLANNING/PLAN_REVIEW/EXECUTING/FINISHED/ARCHIVED',
  start_date DATE,
  end_date DATE,
  location VARCHAR(100),
  organizer VARCHAR(100),
  target_audience VARCHAR(200),
  host VARCHAR(50),
  leader VARCHAR(50),
  description TEXT,
  created_by BIGINT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS activity_plan (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  version INT DEFAULT 1,
  background TEXT,
  purpose TEXT,
  content TEXT,
  flow TEXT COMMENT 'JSON数组 [{step:"...",detail:"..."}]',
  notice TEXT,
  emergency TEXT,
  budget TEXT COMMENT 'JSON数组 [{item,quantity,unitPrice,totalPrice}]',
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/APPROVED/REJECTED',
  submitter_id BIGINT,
  reviewer_id BIGINT,
  review_comment TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_plan_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS activity_agenda (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  step_no INT NOT NULL,
  title VARCHAR(200) NOT NULL,
  remark VARCHAR(500),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_agenda_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS seat_map (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  room_name VARCHAR(50),
  zone VARCHAR(100) COMMENT '如 第一党支部/工作人员/礼仪',
  row_no INT,
  col_no INT,
  person_name VARCHAR(50),
  seat_type VARCHAR(50),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_seat_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS score_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  dimension_name VARCHAR(50) NOT NULL,
  full_marks INT NOT NULL,
  sort_order INT DEFAULT 0,
  CONSTRAINT fk_rule_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS score_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  team_name VARCHAR(100) NOT NULL,
  group_name VARCHAR(100),
  dimension_scores TEXT COMMENT 'JSON对象 {dimensionId: score}',
  total INT,
  rank_no INT,
  remark VARCHAR(200),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_score_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS signin (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  person_id BIGINT,
  name VARCHAR(50) NOT NULL,
  student_no VARCHAR(20),
  class_name VARCHAR(100),
  identity_type VARCHAR(30) COMMENT '党建干事/发展对象/预备党员/入党积极分子',
  sign_type VARCHAR(10) NOT NULL DEFAULT 'MANUAL' COMMENT 'SCAN/MANUAL',
  sign_time DATETIME,
  location VARCHAR(255),
  phone VARCHAR(20),
  remark VARCHAR(200),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_signin_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS task (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  dept_id BIGINT,
  assignee VARCHAR(50) COMMENT '负责人姓名',
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  depends_on BIGINT COMMENT '前置任务id（甘特图依赖线）',
  is_milestone TINYINT DEFAULT 0,
  progress INT DEFAULT 0 COMMENT '0-100',
  status VARCHAR(20) NOT NULL DEFAULT 'TODO' COMMENT 'TODO/DOING/DONE/DELAYED',
  priority INT DEFAULT 0,
  description TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  CONSTRAINT fk_task_activity FOREIGN KEY (activity_id) REFERENCES activity(id),
  CONSTRAINT fk_task_dep FOREIGN KEY (depends_on) REFERENCES task(id)
);

-- ===================== 三、例行事务（排班/考勤/无课表） =====================
CREATE TABLE IF NOT EXISTS schedule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  schedule_type VARCHAR(20) NOT NULL COMMENT 'SMOKING_CURB/CLASS_DUTY/BOOTH/ARCHIVE/STAMP/CLASS_CHECK',
  activity_id BIGINT,
  week_no INT COMMENT '周次',
  weekday INT COMMENT '1-7 周一~周日',
  session_name VARCHAR(50) COMMENT '节次或时间段，如 上午第1-2节 / 9:00-9:10',
  location VARCHAR(100),
  schedule_date DATE,
  notes TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_schedule_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS schedule_person (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  schedule_id BIGINT NOT NULL,
  user_id BIGINT,
  person_name VARCHAR(50) NOT NULL,
  is_primary TINYINT DEFAULT 1,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_sp_schedule FOREIGN KEY (schedule_id) REFERENCES schedule(id)
);

CREATE TABLE IF NOT EXISTS attendance (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  schedule_id BIGINT NOT NULL,
  person_id BIGINT,
  person_name VARCHAR(50) NOT NULL,
  status VARCHAR(10) NOT NULL DEFAULT 'PRESENT' COMMENT 'PRESENT/ABSENT/LEAVE',
  remark VARCHAR(200),
  record_time DATETIME,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_att_schedule FOREIGN KEY (schedule_id) REFERENCES schedule(id)
);

CREATE TABLE IF NOT EXISTS free_schedule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT,
  person_name VARCHAR(50) NOT NULL,
  class_name VARCHAR(100),
  dept_id BIGINT,
  free_weeks TEXT COMMENT 'JSON数组 [1,3,5] 或 {start:1,end:18}',
  note VARCHAR(200),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- ===================== 四、党务台账 =====================
CREATE TABLE IF NOT EXISTS party_member (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(50) NOT NULL,
  gender VARCHAR(10),
  nation VARCHAR(30),
  id_card VARCHAR(18),
  birth_date DATE,
  native_place VARCHAR(100),
  education VARCHAR(50),
  phone VARCHAR(20),
  home_address VARCHAR(255),
  class_name VARCHAR(100),
  college VARCHAR(100),
  branch_name VARCHAR(100) COMMENT '所在党支部',
  political_status VARCHAR(30) COMMENT '共青团员/入党积极分子/预备党员/正式党员',
  student_no VARCHAR(20),
  remark TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS party_stage (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  member_id BIGINT NOT NULL,
  stage VARCHAR(20) NOT NULL COMMENT 'APPLICANT/ACTIVE/DEVELOPMENT/PROBATIONARY/FULL',
  issue_no VARCHAR(20) COMMENT '期数，如 39/40/41',
  status VARCHAR(20) DEFAULT 'CURRENT',
  start_date DATE,
  end_date DATE,
  remark VARCHAR(200),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_stage_member FOREIGN KEY (member_id) REFERENCES party_member(id)
);

CREATE TABLE IF NOT EXISTS party_roster (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  roster_type VARCHAR(30) NOT NULL COMMENT 'RECOMMEND/PASSED/SUMMARY/DEVELOPMENT/TRANSFER',
  issue_no VARCHAR(20),
  name VARCHAR(50) NOT NULL,
  gender VARCHAR(10),
  student_no VARCHAR(20),
  class_name VARCHAR(100),
  branch_name VARCHAR(100),
  remark VARCHAR(200),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS party_investigation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  member_id BIGINT NOT NULL,
  father_name VARCHAR(50),
  father_branch VARCHAR(100),
  father_branch_addr VARCHAR(255),
  mother_name VARCHAR(50),
  mother_branch VARCHAR(100),
  mother_branch_addr VARCHAR(255),
  relative_name VARCHAR(50),
  relative_branch VARCHAR(100),
  relative_branch_addr VARCHAR(255),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_inv_member FOREIGN KEY (member_id) REFERENCES party_member(id)
);

CREATE TABLE IF NOT EXISTS party_register (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  member_id BIGINT NOT NULL,
  college VARCHAR(100),
  branch VARCHAR(100),
  class_name VARCHAR(100),
  name VARCHAR(50),
  gender VARCHAR(10),
  birth_date DATE,
  native_place VARCHAR(100),
  nation VARCHAR(30),
  id_card VARCHAR(18),
  phone VARCHAR(20),
  home_address VARCHAR(255),
  apply_date DATE COMMENT '申请书时间',
  education VARCHAR(50),
  talk_person VARCHAR(50) COMMENT '谈话人',
  condition_note TEXT,
  remark TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_reg_member FOREIGN KEY (member_id) REFERENCES party_member(id)
);

CREATE TABLE IF NOT EXISTS party_transfer (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  member_id BIGINT NOT NULL,
  class_name VARCHAR(100),
  name VARCHAR(50),
  gender VARCHAR(10),
  nation VARCHAR(30),
  is_probationary TINYINT DEFAULT 0,
  id_card VARCHAR(18),
  receive_org VARCHAR(200) COMMENT '接收组织关系的党组织名称',
  phone VARCHAR(20),
  wechat VARCHAR(50),
  is_online TINYINT DEFAULT 1 COMMENT '线上/线下发起介绍信',
  sign_date DATE,
  remark VARCHAR(200),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_transfer_member FOREIGN KEY (member_id) REFERENCES party_member(id)
);

-- ===================== 五、内容与宣传 =====================
CREATE TABLE IF NOT EXISTS article (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(150) NOT NULL,
  summary VARCHAR(500),
  content TEXT NOT NULL,
  cover_url VARCHAR(255),
  activity_id BIGINT,
  article_type VARCHAR(20) NOT NULL DEFAULT 'REPORT' COMMENT 'PREHEAT预热/REPORT报道/VIDEO宣传视频',
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/APPROVED/PUBLISHED/REJECTED',
  author_id BIGINT,
  reviewer_id BIGINT,
  review_comment TEXT,
  publish_time DATETIME,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  CONSTRAINT fk_article_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS news (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(150) NOT NULL,
  subtitle VARCHAR(300),
  content TEXT NOT NULL,
  activity_id BIGINT,
  author_id BIGINT,
  publish_date DATE,
  status VARCHAR(20) DEFAULT 'DRAFT',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  CONSTRAINT fk_news_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

-- ===================== 六、档案与资产 =====================
CREATE TABLE IF NOT EXISTS file_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  filename VARCHAR(255) NOT NULL,
  stored_name VARCHAR(255) NOT NULL,
  path VARCHAR(500) NOT NULL,
  size BIGINT DEFAULT 0,
  content_type VARCHAR(100),
  biz_type VARCHAR(30),
  uploader_id BIGINT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS material (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(150) NOT NULL,
  biz_type VARCHAR(30) NOT NULL COMMENT 'SIGNIN/SCHEUDLE/ATTENDANCE/PLAN/NEWS/ARTICLE/PHOTO/PPT/INVOICE/ROSTER/OTHER',
  activity_id BIGINT,
  dept_id BIGINT,
  uploader_id BIGINT,
  tag VARCHAR(200),
  description VARCHAR(500),
  file_id BIGINT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0,
  CONSTRAINT fk_material_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS template_asset (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(150) NOT NULL,
  category VARCHAR(30) NOT NULL COMMENT 'PLAN/SEAT/AGENDA/SIGNIN/NAMEPLATE/LOGO/EMBER/NEWS',
  description VARCHAR(500),
  file_id BIGINT,
  created_by BIGINT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS credit_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT,
  person_name VARCHAR(50) NOT NULL,
  student_no VARCHAR(20),
  activity_id BIGINT,
  project VARCHAR(100) NOT NULL,
  credit DECIMAL(4,2) NOT NULL,
  basis VARCHAR(30) COMMENT 'PARTICIPATE参与/ANSWER答题',
  remark VARCHAR(200),
  record_by BIGINT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_credit_activity FOREIGN KEY (activity_id) REFERENCES activity(id)
);

CREATE TABLE IF NOT EXISTS announcement (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(150) NOT NULL,
  content TEXT NOT NULL,
  publisher_id BIGINT,
  publish_time DATETIME,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0
);
