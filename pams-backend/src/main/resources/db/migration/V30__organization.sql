-- ===================== 组织（租户）基座 + sys_department 演进为架构树 =====================
-- 双库兼容（MySQL 8 / H2 MySQL 模式）：纯声明式 DDL，不使用 SET/PREPARE/存储过程。
-- 迁移号：紧随 V29。对应《PAMS-通用化-阶段1实施清单.md》§2.1。
--
-- 阶段 1 原则「只写不过滤」：本迁移只建表/加列，不启用任何租户自动过滤。

-- ---------------------------------------------------------------------------
-- 1. organization 组织（租户）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS organization (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL COMMENT '组织全称',
  type VARCHAR(30) NOT NULL DEFAULT 'OTHER'
    COMMENT 'PARTY/STUDENT_UNION/CLUB/VOLUNTEER/SCIENCE/OTHER',
  short_name VARCHAR(50),
  logo_url VARCHAR(255),
  theme_json JSON NULL COMMENT '主题令牌 {primaryColor,...}',
  enabled_packs JSON NULL COMMENT '启用的领域包 ["party","volunteer"]',
  terminology_json JSON NULL COMMENT '术语表 {activity:"活动",member:"成员"}',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  owner_user_id BIGINT NULL COMMENT '注册人/负责人',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织（租户）';

-- ---------------------------------------------------------------------------
-- 2. org_position 职务（映射系统角色，可选）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS org_position (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  org_id BIGINT NOT NULL,
  name VARCHAR(50) NOT NULL COMMENT '职务名：主席/部长/干事',
  level INT NOT NULL DEFAULT 1,
  role_code VARCHAR(30) NOT NULL COMMENT '映射系统角色码 TEACHER/DIRECTOR/*_LEADER/STAFF',
  sort_order INT DEFAULT 0,
  INDEX idx_org_pos_org (org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织-职务';

-- ---------------------------------------------------------------------------
-- 3. org_invitation 邀请入组
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS org_invitation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  org_id BIGINT NOT NULL,
  code VARCHAR(64) NOT NULL,
  role_code VARCHAR(30) NOT NULL,
  org_unit_id BIGINT NULL COMMENT '预约部门(=sys_department.id)',
  expire_at DATETIME NULL,
  used_by BIGINT NULL,
  used_at DATETIME NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_org_inv_code (code),
  INDEX idx_org_inv_org (org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织-邀请入组';

-- ---------------------------------------------------------------------------
-- 4. 默认组织（承接现有全部数据）。照 V8 的幂等写法（双库兼容，无需 FROM DUAL）
-- ---------------------------------------------------------------------------
INSERT INTO organization (id, name, type, short_name, status)
SELECT 1, '默认组织', 'OTHER', '默认', 1
WHERE NOT EXISTS (SELECT 1 FROM organization WHERE id = 1);

-- ---------------------------------------------------------------------------
-- 5. sys_department 原地演进为「组织架构树」：逐列 ADD（双库兼容；Flyway 保证只跑一次）
--    刻意不新建第二套部门表，避免与 seat_map/seat_layout 同款的双轨维护问题。
-- ---------------------------------------------------------------------------
ALTER TABLE sys_department ADD COLUMN org_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE sys_department ADD COLUMN parent_id BIGINT NULL;
ALTER TABLE sys_department ADD COLUMN unit_type VARCHAR(20) NOT NULL DEFAULT '部门';
ALTER TABLE sys_department ADD COLUMN code VARCHAR(50) NULL;
ALTER TABLE sys_department ADD COLUMN level INT NOT NULL DEFAULT 1;
ALTER TABLE sys_department ADD COLUMN path VARCHAR(255) NULL;
ALTER TABLE sys_department ADD COLUMN leader_user_id BIGINT NULL;

-- 注：sys_department.name 的「全局唯一」降级为「组织内唯一」属于复合唯一键重构，
--     与其余唯一键（sys_user.username / member_session.name / kb_chunk / kb_snapshot）
--     一并在阶段 2 处理（见实施清单 §2.3）。阶段 1 保持现状；
--     影响：注册新组织时，若其架构节点名与默认组织的 4 个部门同名（文秘部/组织部/
--     新媒体中心/青年科技部）会触发唯一冲突——阶段 1 的注册演示请用不同名。
