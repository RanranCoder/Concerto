-- ===================== 活动排班与考勤重构（V21） =====================
-- 1) 扩展 schedule 承载“活动排班”（type=ACTIVITY + 具体时间 + 状态 + 生成来源）
-- 2) 扩展 attendance 承载新考勤状态 / status_source / 学号 / 有效签到时间
-- 3) 新增 activity_session（签到场次 / 二维码 token）与 attendance_audit（人工修改审计）
-- 4) 新增权限：schedule:generate / attendance:view / attendance:manage
-- 双兼容写法参考 V7/V10/V15/V20（MySQL + H2 MySQL 模式）。

-- ===================== 扩展 schedule =====================
ALTER TABLE schedule ADD COLUMN start_time TIME COMMENT '活动开始时间，如 14:00';
ALTER TABLE schedule ADD COLUMN end_time TIME COMMENT '活动结束时间，如 16:00';
ALTER TABLE schedule ADD COLUMN status VARCHAR(20) COMMENT 'DRAFT待发布/PUBLISHED已发布/ENDED已结束/CANCELLED已取消';
ALTER TABLE schedule ADD COLUMN source VARCHAR(20) COMMENT 'AUTO无课表自动/MANUAL手动';
ALTER TABLE schedule ADD COLUMN semester VARCHAR(20) COMMENT '排班生成学期';
ALTER TABLE schedule ADD COLUMN dept_id BIGINT COMMENT '排班生成部门';
ALTER TABLE schedule ADD COLUMN dept_name VARCHAR(50) COMMENT '排班生成部门名';

-- ===================== 扩展 attendance =====================
ALTER TABLE attendance MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
  COMMENT 'PENDING待签到/NORMAL正常/LATE迟到/ABSENT缺勤/LEAVE请假/EARLY_LEAVE早退/MAKEUP补签/CANCELED取消';
ALTER TABLE attendance ADD COLUMN session_id BIGINT COMMENT '签到场次id';
ALTER TABLE attendance ADD COLUMN student_no VARCHAR(30) COMMENT '冗余学号，便于列表展示';
ALTER TABLE attendance ADD COLUMN status_source VARCHAR(10) DEFAULT 'AUTO'
  COMMENT 'AUTO自动判断/MANUAL人工修改（MANUAL 不被自动任务覆盖）';
ALTER TABLE attendance ADD COLUMN checked_in_at DATETIME COMMENT '有效签到时间（判定正常/迟到）';

CREATE INDEX idx_attendance_session ON attendance (session_id);

-- ===================== 扩展 signin =====================
ALTER TABLE signin ADD COLUMN session_id BIGINT COMMENT '签到场次id';
CREATE INDEX idx_signin_session ON signin (session_id);

-- ===================== 新增 activity_session（签到场次） =====================
CREATE TABLE IF NOT EXISTS activity_session (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL COMMENT '所属活动',
  schedule_id BIGINT COMMENT '关联排班（可空）',
  session_name VARCHAR(50) COMMENT '场次名，如 下午场',
  session_date DATE COMMENT '场次日期',
  start_time TIME COMMENT '活动开始时间',
  end_time TIME COMMENT '活动结束时间',
  checkin_window_start TIME COMMENT '签到开启边界（如 13:45）',
  checkin_window_end TIME COMMENT '正常签到截止（如 14:15，超此即迟到）',
  signin_enabled TINYINT DEFAULT 1 COMMENT '0关闭 1开启',
  status VARCHAR(20) NOT NULL DEFAULT 'CLOSED' COMMENT 'OPEN/CLOSED/ENDED/CANCELLED',
  token VARCHAR(64) COMMENT '临时签到token',
  token_expires_at DATETIME COMMENT 'token过期时间',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_session_activity (activity_id),
  KEY idx_session_schedule (schedule_id)
) COMMENT='签到场次';

-- ===================== 新增 attendance_audit（人工修改审计） =====================
CREATE TABLE IF NOT EXISTS attendance_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  attendance_id BIGINT NOT NULL COMMENT '关联考勤记录',
  old_status VARCHAR(20) COMMENT '原状态',
  new_status VARCHAR(20) COMMENT '新状态',
  operator_id BIGINT COMMENT '操作人id',
  operator_name VARCHAR(50) COMMENT '操作人姓名',
  operate_time DATETIME COMMENT '操作时间',
  remark VARCHAR(255) COMMENT '修改原因/备注',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_audit_attendance (attendance_id)
) COMMENT='人工考勤修改审计';

-- ===================== 权限种子 =====================
INSERT IGNORE INTO permission (code, name, module, sort_order) VALUES
('schedule:generate', '一键生成排班', 'schedule', 5),
('attendance:view', '查看考勤中心', 'attendance', 1),
('attendance:manage', '人工考勤管理', 'attendance', 2);

-- TEACHER / DIRECTOR：全量或除 user:permission 外全量，自动覆盖新增权限
INSERT IGNORE INTO role_permission (role, permission_id)
SELECT 'TEACHER', id FROM permission
WHERE code IN ('schedule:generate', 'attendance:view', 'attendance:manage');
INSERT IGNORE INTO role_permission (role, permission_id)
SELECT 'DIRECTOR', id FROM permission
WHERE code IN ('schedule:generate', 'attendance:view', 'attendance:manage');

-- 四部长：排班生成 + 考勤中心
INSERT IGNORE INTO role_permission (role, permission_id)
SELECT 'ORG_LEADER', id FROM permission
WHERE code IN ('schedule:generate', 'attendance:view', 'attendance:manage');
INSERT IGNORE INTO role_permission (role, permission_id)
SELECT 'SECRETARY_LEADER', id FROM permission
WHERE code IN ('schedule:generate', 'attendance:view', 'attendance:manage');
INSERT IGNORE INTO role_permission (role, permission_id)
SELECT 'MEDIA_LEADER', id FROM permission
WHERE code IN ('schedule:generate', 'attendance:view', 'attendance:manage');
INSERT IGNORE INTO role_permission (role, permission_id)
SELECT 'TECH_LEADER', id FROM permission
WHERE code IN ('schedule:generate', 'attendance:view', 'attendance:manage');

-- STAFF（干事）：考勤中心查看 + 人工考勤管理（签到打卡场景）
INSERT IGNORE INTO role_permission (role, permission_id)
SELECT 'STAFF', id FROM permission
WHERE code IN ('attendance:view', 'attendance:manage');