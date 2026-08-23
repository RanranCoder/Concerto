-- C1 修复：补建 CourseSchedule / ScheduleConfig 两张活跃实体表
-- 审计发现 V1~V14 全部迁移均未建表，docker 全新部署后 /api/course-schedules 整组接口 500。
-- 列定义与实体逐列对齐（CourseSchedule / ScheduleConfig），MySQL 与 H2(MySQL 模式) 双兼容。
-- 索引内联于 CREATE TABLE（MySQL 不支持 CREATE INDEX IF NOT EXISTS，故不单独建索引）。

-- 个人课程表（PRD F08）
CREATE TABLE IF NOT EXISTS course_schedule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL COMMENT '所属用户',
  semester VARCHAR(20) COMMENT '学期，如 2025-2026-1',
  day_of_week INT NOT NULL COMMENT '星期几 1-7',
  period INT NOT NULL COMMENT '节次',
  course_name VARCHAR(100) COMMENT '课程名，空表示仅占位"有课"',
  created_at DATETIME,
  updated_at DATETIME,
  -- 高频查询：按用户查学期课表 / 覆盖式删除（CourseScheduleRepository）
  KEY idx_course_schedule_user_semester (user_id, semester),
  KEY idx_course_schedule_user (user_id)
);

-- 时间格配置（PRD F08.4）
CREATE TABLE IF NOT EXISTS schedule_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  period INT NOT NULL COMMENT '节次序号',
  start_time TIME COMMENT '开始时间',
  end_time TIME COMMENT '结束时间',
  label VARCHAR(20) COMMENT '展示标签，如"第1-2节"',
  -- ScheduleConfigRepository.findAllByOrderByPeriodAsc
  KEY idx_schedule_config_period (period)
);
