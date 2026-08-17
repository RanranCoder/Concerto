-- ===================== member 模块权限码（RBAC 接通） =====================
-- 成员花名册模块此前未纳入权限管理树，补齐 4 个权限点 + 各角色默认映射。
-- 与 V7__permission.sql 同口径：TEACHER 全量、DIRECTOR 全量、四部长不含导入账号、STAFF 无（仅干部可见）。

INSERT IGNORE INTO permission (code, name, module, sort_order) VALUES
('member:view',          '查看成员',     'member', 1),
('member:manage',         '成员管理',     'member', 2),
('member:export',        '导出成员',     'member', 3),
('member:import_account', '从花名册导入账号', 'member', 4);

-- TEACHER（指导老师）/ DIRECTOR（主任）：member 模块全部
INSERT IGNORE INTO role_permission (role, permission_id)
SELECT r.role, p.id FROM
(SELECT 'TEACHER' AS role UNION ALL SELECT 'DIRECTOR') r
CROSS JOIN permission p
WHERE p.module = 'member';

-- 四部长：view + manage + export（不含 import_account，沿用原 ADMIN=TEACHER+DIRECTOR 口径）
INSERT IGNORE INTO role_permission (role, permission_id)
SELECT r.role, p.id FROM
(SELECT 'ORG_LEADER' AS role
 UNION ALL SELECT 'SECRETARY_LEADER'
 UNION ALL SELECT 'MEDIA_LEADER'
 UNION ALL SELECT 'TECH_LEADER') r
CROSS JOIN permission p
WHERE p.code IN ('member:view', 'member:manage', 'member:export');

-- STAFF（干事）：无 member 权限，保持「仅干部可见」现状
