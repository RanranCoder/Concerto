-- ===================== F07 权限管理（RBAC） =====================

-- 权限点表
CREATE TABLE IF NOT EXISTS permission (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(100) NOT NULL UNIQUE COMMENT '权限码 {模块}:{操作}',
  name VARCHAR(100) NOT NULL COMMENT '权限名',
  module VARCHAR(50) COMMENT '所属模块',
  parent_id BIGINT NULL COMMENT '父权限ID（预留，当前为平铺）',
  sort_order INT DEFAULT 0 COMMENT '模块内排序',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_permission_module (module)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限点';

-- 角色-权限关联表
CREATE TABLE IF NOT EXISTS role_permission (
  role VARCHAR(20) NOT NULL COMMENT '角色码',
  permission_id BIGINT NOT NULL COMMENT '权限ID',
  PRIMARY KEY (role, permission_id),
  CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES permission(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-权限关联';

-- ===================== 权限点种子（附录B.1 共 43 项） =====================
INSERT INTO permission (code, name, module, sort_order) VALUES
('activity:view', '查看活动', 'activity', 1),
('activity:create', '创建活动', 'activity', 2),
('activity:edit', '编辑活动', 'activity', 3),
('activity:delete', '删除活动', 'activity', 4),
('activity:assign', '分配部门', 'activity', 5),
('activity:review', '审核策划书', 'activity', 6),
('checkin:view', '查看签到', 'checkin', 1),
('checkin:manage', '签到管理', 'checkin', 2),
('checkin:export', '导出签到', 'checkin', 3),
('material:view', '查看材料', 'material', 1),
('material:upload', '上传材料', 'material', 2),
('material:download', '下载材料', 'material', 3),
('material:delete', '删除材料', 'material', 4),
('material:preview', '预览材料', 'material', 5),
('template:view', '查看模板', 'template', 1),
('template:use', '使用模板', 'template', 2),
('template:manage', '模板管理', 'template', 3),
('seat:view', '查看座位', 'seat', 1),
('seat:edit', '编辑座位', 'seat', 2),
('seat:template', '座位模板', 'seat', 3),
('quality:view', '查看素拓', 'quality', 1),
('quality:add', '手动加分', 'quality', 2),
('quality:activity_add', '批量加分', 'quality', 3),
('quality:delete', '删除加分', 'quality', 4),
('chat:view', '查看群聊', 'chat', 1),
('chat:manage', '群聊管理', 'chat', 2),
('chat:category', '群聊分类', 'chat', 3),
('notice:view', '查看公告', 'notice', 1),
('notice:publish', '发布公告', 'notice', 2),
('notice:manage', '公告管理', 'notice', 3),
('schedule:view', '查看排班', 'schedule', 1),
('schedule:manage', '排班管理', 'schedule', 2),
('schedule:check', '考勤打卡', 'schedule', 3),
('schedule:free_table', '无课表', 'schedule', 4),
('party:view', '查看党员发展', 'party', 1),
('party:manage', '发展流程管理', 'party', 2),
('party:letter', '函调管理', 'party', 3),
('party:entry', '党务录入', 'party', 4),
('user:view', '查看用户', 'user', 1),
('user:manage', '用户管理', 'user', 2),
('user:permission', '权限管理', 'user', 3),
('notification:view', '查看通知', 'notification', 1),
('notification:preference', '通知偏好', 'notification', 2);

-- ===================== 角色默认映射（附录B.2，与 PermissionService.restoreDefault 一致） =====================

-- TEACHER（指导老师）：全部权限
INSERT INTO role_permission (role, permission_id)
SELECT 'TEACHER', id FROM permission;

-- DIRECTOR（主任）：除 user:permission 外全部
INSERT INTO role_permission (role, permission_id)
SELECT 'DIRECTOR', id FROM permission WHERE code <> 'user:permission';

-- 四部长（组织/文秘/新媒体/青年科技）：部长级操作集（不含删除/分配/分类/用户管理）
INSERT INTO role_permission (role, permission_id)
SELECT 'ORG_LEADER', id FROM permission WHERE code IN (
  'activity:view','activity:create','activity:edit','activity:review',
  'checkin:view','checkin:manage','checkin:export',
  'material:view','material:upload','material:download','material:preview',
  'template:view','template:use',
  'seat:view','seat:edit','seat:template',
  'quality:view','quality:add','quality:activity_add',
  'chat:view','chat:manage',
  'notice:view','notice:publish',
  'schedule:view','schedule:manage','schedule:check','schedule:free_table',
  'party:view','party:manage','party:letter','party:entry',
  'notification:view','notification:preference');
INSERT INTO role_permission (role, permission_id)
SELECT 'SECRETARY_LEADER', id FROM permission WHERE code IN (
  'activity:view','activity:create','activity:edit','activity:review',
  'checkin:view','checkin:manage','checkin:export',
  'material:view','material:upload','material:download','material:preview',
  'template:view','template:use',
  'seat:view','seat:edit','seat:template',
  'quality:view','quality:add','quality:activity_add',
  'chat:view','chat:manage',
  'notice:view','notice:publish',
  'schedule:view','schedule:manage','schedule:check','schedule:free_table',
  'party:view','party:manage','party:letter','party:entry',
  'notification:view','notification:preference');
INSERT INTO role_permission (role, permission_id)
SELECT 'MEDIA_LEADER', id FROM permission WHERE code IN (
  'activity:view','activity:create','activity:edit','activity:review',
  'checkin:view','checkin:manage','checkin:export',
  'material:view','material:upload','material:download','material:preview',
  'template:view','template:use',
  'seat:view','seat:edit','seat:template',
  'quality:view','quality:add','quality:activity_add',
  'chat:view','chat:manage',
  'notice:view','notice:publish',
  'schedule:view','schedule:manage','schedule:check','schedule:free_table',
  'party:view','party:manage','party:letter','party:entry',
  'notification:view','notification:preference');
INSERT INTO role_permission (role, permission_id)
SELECT 'TECH_LEADER', id FROM permission WHERE code IN (
  'activity:view','activity:create','activity:edit','activity:review',
  'checkin:view','checkin:manage','checkin:export',
  'material:view','material:upload','material:download','material:preview',
  'template:view','template:use',
  'seat:view','seat:edit','seat:template',
  'quality:view','quality:add','quality:activity_add',
  'chat:view','chat:manage',
  'notice:view','notice:publish',
  'schedule:view','schedule:manage','schedule:check','schedule:free_table',
  'party:view','party:manage','party:letter','party:entry',
  'notification:view','notification:preference');

-- STAFF（干事）：基础操作集
INSERT INTO role_permission (role, permission_id)
SELECT 'STAFF', id FROM permission WHERE code IN (
  'activity:view',
  'checkin:view','checkin:manage','checkin:export',
  'material:view','material:upload','material:download','material:preview',
  'template:view','template:use',
  'seat:view','seat:edit',
  'quality:view',
  'chat:view',
  'notice:view',
  'schedule:view','schedule:check','schedule:free_table',
  'party:view',
  'notification:view','notification:preference');
