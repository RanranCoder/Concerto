-- 仅供新建的演示库使用。先完成 Flyway 迁移及应用的角色初始化。
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS=0;
DELETE FROM member;
DELETE FROM member_session;
DELETE FROM org_position;
DELETE FROM sys_user;
DELETE FROM sys_department;
UPDATE organization SET name='协奏演示组织', short_name='协奏', type='OTHER',
  theme_json='{"primaryColor":"#315F9B"}', enabled_packs='["party","volunteer","club"]',
  terminology_json='{"activity":"活动","member":"成员"}', status=1, owner_user_id=1, updated_at=NOW() WHERE id=1;
UPDATE org_domain_pack SET enabled=1 WHERE org_id=1 AND pack_code IN ('party','volunteer','club');
UPDATE sys_role SET name='宣传部长' WHERE code='MEDIA_LEADER' AND org_id=1;
UPDATE sys_role SET name='秘书长' WHERE code='SECRETARY_LEADER' AND org_id=1;
INSERT INTO sys_department (id,org_id,parent_id,name,unit_type,code,level,path,leader_user_id,sort_order,created_at,updated_at) VALUES
(1,1,NULL,'主任','部门','DIRECTOR',1,'/1',1,1,NOW(),NOW()),
(2,1,NULL,'宣传部','部门','MEDIA',1,'/2',2,2,NOW(),NOW()),
(3,1,NULL,'组织部','部门','ORG',1,'/3',3,3,NOW(),NOW()),
(4,1,NULL,'秘书处','部门','SECRETARY',1,'/4',4,4,NOW(),NOW());
-- 所有账号密码为 123456，演示专用。
INSERT INTO sys_user (id,org_id,username,password,real_name,student_no,phone,dept_id,role_id,status,created_at,updated_at,deleted) VALUES
(1,1,'admin','$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm','陈知远','20260001','13800000001',1,2,1,NOW(),NOW(),0),
(2,1,'media','$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm','林若宁','20260002','13800000002',2,5,1,NOW(),NOW(),0),
(3,1,'org','$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm','周明轩','20260003','13800000003',3,3,1,NOW(),NOW(),0),
(4,1,'secretary','$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm','许清禾','20260004','13800000004',4,4,1,NOW(),NOW(),0),
(5,1,'media01','$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm','苏言','20260005','13800000005',2,7,1,NOW(),NOW(),0),
(6,1,'media02','$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm','宋雨桐','20260006','13800000006',2,7,1,NOW(),NOW(),0),
(7,1,'media03','$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm','叶星辰','20260007','13800000007',2,7,1,NOW(),NOW(),0),
(8,1,'media04','$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm','唐悦','20260008','13800000008',2,7,1,NOW(),NOW(),0),
(9,1,'org01','$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm','顾嘉宁','20260009','13800000009',3,7,1,NOW(),NOW(),0),
(10,1,'org02','$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm','沈亦航','20260010','13800000010',3,7,1,NOW(),NOW(),0),
(11,1,'org03','$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm','江小满','20260011','13800000011',3,7,1,NOW(),NOW(),0),
(12,1,'org04','$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm','陆言川','20260012','13800000012',3,7,1,NOW(),NOW(),0),
(13,1,'sec01','$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm','方可欣','20260013','13800000013',4,7,1,NOW(),NOW(),0),
(14,1,'sec02','$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm','夏沐阳','20260014','13800000014',4,7,1,NOW(),NOW(),0),
(15,1,'sec03','$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm','白静宜','20260015','13800000015',4,7,1,NOW(),NOW(),0),
(16,1,'sec04','$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm','韩景行','20260016','13800000016',4,7,1,NOW(),NOW(),0);
INSERT INTO org_position (org_id,name,level,role_code,sort_order) VALUES
(1,'主任',4,'DIRECTOR',1),(1,'宣传部长',3,'MEDIA_LEADER',2),(1,'组织部长',3,'ORG_LEADER',3),(1,'秘书长',3,'SECRETARY_LEADER',4),(1,'干事',1,'STAFF',5);
INSERT INTO member_session (id,name,is_current,sort_order,remark,created_at,updated_at,deleted,org_id)
VALUES (1,'第一届',1,1,'协奏演示组织当前届',NOW(),NOW(),0,1);
INSERT INTO member (id,session_id,dept_id,position,name,gender,student_no,class_name,phone,political_status,status,remark,created_by,created_at,updated_at,deleted,org_id)
SELECT id,1,dept_id,CASE WHEN id=1 THEN 'DIRECTOR' WHEN id<=4 THEN 'DEPT_HEAD' ELSE 'STAFF' END,
real_name,CASE WHEN MOD(id,2)=0 THEN '女' ELSE '男' END,student_no,'2026级协奏演示班',phone,'共青团员','ACTIVE','虚构演示数据',1,NOW(),NOW(),0,1 FROM sys_user WHERE org_id=1;
SET FOREIGN_KEY_CHECKS=1;
