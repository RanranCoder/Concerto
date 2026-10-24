-- PAMS 演示数据：清空业务数据 + 重建组织架构与全角色账号
-- 生成脚本：scripts/demo-data/generate_demo_seed.py
-- 默认登录密码：123456（仅用于演示，请勿用于生产）
-- 说明：保留 Flyway、权限点、角色模板、组织 1 的授权配置与 Agent 定义；
--       清空其余全部业务数据、成员数据与账号数据。

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 1. 清空业务与账号数据
DELETE FROM `activity`;
DELETE FROM `activity_agenda`;
DELETE FROM `activity_plan`;
DELETE FROM `activity_plan_review`;
DELETE FROM `agent_audit_log`;
DELETE FROM `agent_benchmark`;
DELETE FROM `agent_conversation`;
DELETE FROM `agent_message`;
DELETE FROM `agent_run`;
DELETE FROM `agent_step`;
DELETE FROM `agent_task`;
DELETE FROM `agent_tool_call`;
DELETE FROM `announcement`;
DELETE FROM `announcement_read`;
DELETE FROM `article`;
DELETE FROM `attendance`;
DELETE FROM `attendance_audit`;
DELETE FROM `course_schedule`;
DELETE FROM `credit_record`;
DELETE FROM `file_record`;
DELETE FROM `group_chat`;
DELETE FROM `group_chat_category`;
DELETE FROM `group_chat_department`;
DELETE FROM `kb_chunk`;
DELETE FROM `kb_document`;
DELETE FROM `kb_retrieval_log`;
DELETE FROM `kb_snapshot`;
DELETE FROM `material`;
DELETE FROM `member`;
DELETE FROM `member_account_batch`;
DELETE FROM `member_account_credential`;
DELETE FROM `member_session`;
DELETE FROM `news`;
DELETE FROM `no_class_schedule`;
DELETE FROM `notification`;
DELETE FROM `org_audit_log`;
DELETE FROM `org_authorization_change`;
DELETE FROM `org_invitation`;
DELETE FROM `org_position`;
DELETE FROM `org_role_delegation`;
DELETE FROM `party_investigation`;
DELETE FROM `party_member`;
DELETE FROM `party_register`;
DELETE FROM `party_roster`;
DELETE FROM `party_stage`;
DELETE FROM `party_transfer`;
DELETE FROM `schedule`;
DELETE FROM `schedule_config`;
DELETE FROM `schedule_person`;
DELETE FROM `score_record`;
DELETE FROM `score_rule`;
DELETE FROM `seat_layout`;
DELETE FROM `seat_map`;
DELETE FROM `sign_in_group`;
DELETE FROM `signin`;
DELETE FROM `signin_field_config`;
DELETE FROM `signin_roster`;
DELETE FROM `sys_department`;
DELETE FROM `sys_user`;
DELETE FROM `task`;
DELETE FROM `template_asset`;

-- 2. 移除其它组织及其授权数据，仅保留组织 1 的已发布授权
DELETE FROM role_grant WHERE org_id <> 1 OR role_id NOT IN (1,2,3,4,5,6,7);
DELETE FROM role_revision WHERE org_id <> 1 OR role_id NOT IN (1,2,3,4,5,6,7);
DELETE FROM org_domain_pack WHERE org_id <> 1;
DELETE FROM authorization_bootstrap WHERE org_id <> 1;
DELETE FROM sys_role WHERE id NOT IN (1,2,3,4,5,6,7);
DELETE FROM organization WHERE id <> 1;

-- 3. 组织资料：信息与智能工程学院党建办公室
UPDATE organization SET name='信息与智能工程学院党建办公室', type='PARTY', short_name='信智党建办', theme_json='{"primaryColor": "#A61B29"}', enabled_packs='["party", "volunteer", "club"]', status=1, owner_user_id=1, authz_version=authz_version+1, updated_at=NOW() WHERE id=1;
UPDATE org_domain_pack SET enabled=1, activation_version=1 WHERE org_id=1 AND pack_code='party';
INSERT INTO org_domain_pack(org_id, pack_code, enabled, activation_version) SELECT 1, 'party', 1, 1 WHERE NOT EXISTS (SELECT 1 FROM org_domain_pack WHERE org_id=1 AND pack_code='party');

-- 4. 组织架构（主任办公室 + 四个业务部门，对应图示 5 个部门分组）
INSERT INTO sys_department (id, org_id, parent_id, name, unit_type, code, level, path, leader_user_id, sort_order, created_at, updated_at) VALUES
(1, 1, NULL, '主任办公室', '中心', 'DIRECTOR', 1, '/1', 3, 1, NOW(), NOW()),
(2, 1, NULL, '青年科技部', '部门', 'TECH', 1, '/2', 6, 2, NOW(), NOW()),
(3, 1, NULL, '文秘部', '部门', 'SEC', 1, '/3', 18, 3, NOW(), NOW()),
(4, 1, NULL, '新媒体中心', '中心', 'MEDIA', 1, '/4', 35, 4, NOW(), NOW()),
(5, 1, NULL, '组织部', '部门', 'ORG', 1, '/5', 51, 5, NOW(), NOW());

-- 5. 账号：70 个（68 个图示岗位 + 指导老师 + 系统管理员），密码均为 123456
INSERT INTO sys_user (id, org_id, username, password, real_name, student_no, phone, dept_id, role_id, status, created_at, updated_at, deleted) VALUES
(1, 1, 'admin', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '系统管理员', NULL, NULL, 1, 1, 1, NOW(), NOW(), 0),
(2, 1, 'teacher', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '指导老师', NULL, NULL, 1, 1, 1, NOW(), NOW(), 0),
(3, 1, 'zhuren', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '陈志远', NULL, NULL, 1, 2, 1, NOW(), NOW(), 0),
(4, 1, 'fuzhuren1', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '李慧敏', NULL, NULL, 1, 2, 1, NOW(), NOW(), 0),
(5, 1, 'fuzhuren2', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '周建国', NULL, NULL, 1, 2, 1, NOW(), NOW(), 0),
(6, 1, 'techleader', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '王浩然', NULL, NULL, 2, 6, 1, NOW(), NOW(), 0),
(7, 1, 'techdeputy1', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '刘思远', NULL, NULL, 2, 6, 1, NOW(), NOW(), 0),
(8, 1, 'techstaff01', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '张宇轩', NULL, NULL, 2, 7, 1, NOW(), NOW(), 0),
(9, 1, 'techstaff02', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '李泽楷', NULL, NULL, 2, 7, 1, NOW(), NOW(), 0),
(10, 1, 'techstaff03', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '王雨欣', NULL, NULL, 2, 7, 1, NOW(), NOW(), 0),
(11, 1, 'techstaff04', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '陈嘉豪', NULL, NULL, 2, 7, 1, NOW(), NOW(), 0),
(12, 1, 'techstaff05', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '杨若彤', NULL, NULL, 2, 7, 1, NOW(), NOW(), 0),
(13, 1, 'techstaff06', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '赵子涵', NULL, NULL, 2, 7, 1, NOW(), NOW(), 0),
(14, 1, 'techstaff07', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '黄俊杰', NULL, NULL, 2, 7, 1, NOW(), NOW(), 0),
(15, 1, 'techstaff08', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '吴梦琪', NULL, NULL, 2, 7, 1, NOW(), NOW(), 0),
(16, 1, 'techstaff09', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '徐博文', NULL, NULL, 2, 7, 1, NOW(), NOW(), 0),
(17, 1, 'techstaff10', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '孙悦', NULL, NULL, 2, 7, 1, NOW(), NOW(), 0),
(18, 1, 'secleader', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '林晓彤', NULL, NULL, 3, 4, 1, NOW(), NOW(), 0),
(19, 1, 'secdeputy1', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '郑雅静', NULL, NULL, 3, 4, 1, NOW(), NOW(), 0),
(20, 1, 'secdeputy2', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '何嘉怡', NULL, NULL, 3, 4, 1, NOW(), NOW(), 0),
(21, 1, 'secstaff01', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '周雨桐', NULL, NULL, 3, 7, 1, NOW(), NOW(), 0),
(22, 1, 'secstaff02', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '陈思琪', NULL, NULL, 3, 7, 1, NOW(), NOW(), 0),
(23, 1, 'secstaff03', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '林佳怡', NULL, NULL, 3, 7, 1, NOW(), NOW(), 0),
(24, 1, 'secstaff04', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '黄诗涵', NULL, NULL, 3, 7, 1, NOW(), NOW(), 0),
(25, 1, 'secstaff05', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '吴静怡', NULL, NULL, 3, 7, 1, NOW(), NOW(), 0),
(26, 1, 'secstaff06', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '郑欣妍', NULL, NULL, 3, 7, 1, NOW(), NOW(), 0),
(27, 1, 'secstaff07', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '王梦洁', NULL, NULL, 3, 7, 1, NOW(), NOW(), 0),
(28, 1, 'secstaff08', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '李佳琪', NULL, NULL, 3, 7, 1, NOW(), NOW(), 0),
(29, 1, 'secstaff09', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '张欣然', NULL, NULL, 3, 7, 1, NOW(), NOW(), 0),
(30, 1, 'secstaff10', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '刘诗雨', NULL, NULL, 3, 7, 1, NOW(), NOW(), 0),
(31, 1, 'secstaff11', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '陈雅雯', NULL, NULL, 3, 7, 1, NOW(), NOW(), 0),
(32, 1, 'secstaff12', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '杨子晴', NULL, NULL, 3, 7, 1, NOW(), NOW(), 0),
(33, 1, 'secstaff13', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '赵佳宁', NULL, NULL, 3, 7, 1, NOW(), NOW(), 0),
(34, 1, 'secstaff14', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '徐若曦', NULL, NULL, 3, 7, 1, NOW(), NOW(), 0),
(35, 1, 'medialeader', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '苏子墨', NULL, NULL, 4, 5, 1, NOW(), NOW(), 0),
(36, 1, 'mediadeputy1', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '唐嫣然', NULL, NULL, 4, 5, 1, NOW(), NOW(), 0),
(37, 1, 'mediadeputy2', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '冯亦凡', NULL, NULL, 4, 5, 1, NOW(), NOW(), 0),
(38, 1, 'mediadeputy3', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '董欣怡', NULL, NULL, 4, 5, 1, NOW(), NOW(), 0),
(39, 1, 'mediastaff01', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '陈一鸣', NULL, NULL, 4, 7, 1, NOW(), NOW(), 0),
(40, 1, 'mediastaff02', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '李梓涵', NULL, NULL, 4, 7, 1, NOW(), NOW(), 0),
(41, 1, 'mediastaff03', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '王俊熙', NULL, NULL, 4, 7, 1, NOW(), NOW(), 0),
(42, 1, 'mediastaff04', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '张晨曦', NULL, NULL, 4, 7, 1, NOW(), NOW(), 0),
(43, 1, 'mediastaff05', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '刘思琪', NULL, NULL, 4, 7, 1, NOW(), NOW(), 0),
(44, 1, 'mediastaff06', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '黄子轩', NULL, NULL, 4, 7, 1, NOW(), NOW(), 0),
(45, 1, 'mediastaff07', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '吴嘉怡', NULL, NULL, 4, 7, 1, NOW(), NOW(), 0),
(46, 1, 'mediastaff08', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '周浩宇', NULL, NULL, 4, 7, 1, NOW(), NOW(), 0),
(47, 1, 'mediastaff09', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '徐梦瑶', NULL, NULL, 4, 7, 1, NOW(), NOW(), 0),
(48, 1, 'mediastaff10', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '孙嘉豪', NULL, NULL, 4, 7, 1, NOW(), NOW(), 0),
(49, 1, 'mediastaff11', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '马雨欣', NULL, NULL, 4, 7, 1, NOW(), NOW(), 0),
(50, 1, 'mediastaff12', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '朱子涵', NULL, NULL, 4, 7, 1, NOW(), NOW(), 0),
(51, 1, 'orgleader', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '马嘉诚', NULL, NULL, 5, 3, 1, NOW(), NOW(), 0),
(52, 1, 'orgdeputy1', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '罗诗涵', NULL, NULL, 5, 3, 1, NOW(), NOW(), 0),
(53, 1, 'orgdeputy2', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '高子豪', NULL, NULL, 5, 3, 1, NOW(), NOW(), 0),
(54, 1, 'orgdeputy3', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '谢婉婷', NULL, NULL, 5, 3, 1, NOW(), NOW(), 0),
(55, 1, 'orgstaff01', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '韩雨桐', NULL, NULL, 5, 7, 1, NOW(), NOW(), 0),
(56, 1, 'orgstaff02', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '曹俊杰', NULL, NULL, 5, 7, 1, NOW(), NOW(), 0),
(57, 1, 'orgstaff03', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '邓紫琪', NULL, NULL, 5, 7, 1, NOW(), NOW(), 0),
(58, 1, 'orgstaff04', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '许志强', NULL, NULL, 5, 7, 1, NOW(), NOW(), 0),
(59, 1, 'orgstaff05', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '傅晓彤', NULL, NULL, 5, 7, 1, NOW(), NOW(), 0),
(60, 1, 'orgstaff06', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '沈佳豪', NULL, NULL, 5, 7, 1, NOW(), NOW(), 0),
(61, 1, 'orgstaff07', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '曾雅琳', NULL, NULL, 5, 7, 1, NOW(), NOW(), 0),
(62, 1, 'orgstaff08', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '彭浩然', NULL, NULL, 5, 7, 1, NOW(), NOW(), 0),
(63, 1, 'orgstaff09', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '吕思妍', NULL, NULL, 5, 7, 1, NOW(), NOW(), 0),
(64, 1, 'orgstaff10', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '苏俊宇', NULL, NULL, 5, 7, 1, NOW(), NOW(), 0),
(65, 1, 'orgstaff11', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '蒋若曦', NULL, NULL, 5, 7, 1, NOW(), NOW(), 0),
(66, 1, 'orgstaff12', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '蔡明轩', NULL, NULL, 5, 7, 1, NOW(), NOW(), 0),
(67, 1, 'orgstaff13', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '潘雨欣', NULL, NULL, 5, 7, 1, NOW(), NOW(), 0),
(68, 1, 'orgstaff14', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '杜嘉诚', NULL, NULL, 5, 7, 1, NOW(), NOW(), 0),
(69, 1, 'orgstaff15', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '戴诗涵', NULL, NULL, 5, 7, 1, NOW(), NOW(), 0),
(70, 1, 'orgstaff16', '$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm', '姚子健', NULL, NULL, 5, 7, 1, NOW(), NOW(), 0);

-- 5.1 作废旧 token：水位设为当前时刻，此前签发的 JWT 一律失效
UPDATE sys_user SET token_invalid_before = NOW();

-- 6. 岗位字典
INSERT INTO org_position (id, org_id, name, level, role_code, sort_order) VALUES
(1, 1, '主任', 4, 'DIRECTOR', 1),
(2, 1, '副主任', 4, 'DIRECTOR', 2),
(3, 1, '青年科技部部长', 3, 'TECH_LEADER', 3),
(4, 1, '青年科技部副部长', 3, 'TECH_LEADER', 4),
(5, 1, '文秘部部长', 3, 'SECRETARY_LEADER', 5),
(6, 1, '文秘部副部长', 3, 'SECRETARY_LEADER', 6),
(7, 1, '新媒体中心部长', 3, 'MEDIA_LEADER', 7),
(8, 1, '新媒体中心副部长', 3, 'MEDIA_LEADER', 8),
(9, 1, '组织部部长', 3, 'ORG_LEADER', 9),
(10, 1, '组织部副部长', 3, 'ORG_LEADER', 10),
(11, 1, '干事', 1, 'STAFF', 11),
(12, 1, '指导老师', 5, 'TEACHER', 12);

-- 7. 当前届别
INSERT INTO member_session (id, name, is_current, sort_order, remark, created_at, updated_at, deleted, org_id) VALUES (1, '第九届', 1, 1, NULL, NOW(), NOW(), 0, 1);

-- 7.1 成员名册：与图示 68 个岗位一一对应
INSERT INTO member (id, session_id, dept_id, position, name, gender, student_no, class_name, phone, political_status, status, remark, created_by, created_at, updated_at, deleted, org_id) VALUES
(1, 1, 1, 'DIRECTOR', '陈志远', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(2, 1, 1, 'SUB_DIRECTOR', '李慧敏', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(3, 1, 1, 'SUB_DIRECTOR', '周建国', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(4, 1, 2, 'DEPT_HEAD', '王浩然', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(5, 1, 2, 'SUB_DEPT_HEAD', '刘思远', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(6, 1, 2, 'STAFF', '张宇轩', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(7, 1, 2, 'STAFF', '李泽楷', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(8, 1, 2, 'STAFF', '王雨欣', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(9, 1, 2, 'STAFF', '陈嘉豪', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(10, 1, 2, 'STAFF', '杨若彤', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(11, 1, 2, 'STAFF', '赵子涵', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(12, 1, 2, 'STAFF', '黄俊杰', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(13, 1, 2, 'STAFF', '吴梦琪', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(14, 1, 2, 'STAFF', '徐博文', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(15, 1, 2, 'STAFF', '孙悦', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(16, 1, 3, 'DEPT_HEAD', '林晓彤', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(17, 1, 3, 'SUB_DEPT_HEAD', '郑雅静', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(18, 1, 3, 'SUB_DEPT_HEAD', '何嘉怡', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(19, 1, 3, 'STAFF', '周雨桐', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(20, 1, 3, 'STAFF', '陈思琪', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(21, 1, 3, 'STAFF', '林佳怡', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(22, 1, 3, 'STAFF', '黄诗涵', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(23, 1, 3, 'STAFF', '吴静怡', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(24, 1, 3, 'STAFF', '郑欣妍', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(25, 1, 3, 'STAFF', '王梦洁', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(26, 1, 3, 'STAFF', '李佳琪', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(27, 1, 3, 'STAFF', '张欣然', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(28, 1, 3, 'STAFF', '刘诗雨', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(29, 1, 3, 'STAFF', '陈雅雯', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(30, 1, 3, 'STAFF', '杨子晴', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(31, 1, 3, 'STAFF', '赵佳宁', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(32, 1, 3, 'STAFF', '徐若曦', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(33, 1, 4, 'DEPT_HEAD', '苏子墨', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(34, 1, 4, 'SUB_DEPT_HEAD', '唐嫣然', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(35, 1, 4, 'SUB_DEPT_HEAD', '冯亦凡', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(36, 1, 4, 'SUB_DEPT_HEAD', '董欣怡', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(37, 1, 4, 'STAFF', '陈一鸣', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(38, 1, 4, 'STAFF', '李梓涵', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(39, 1, 4, 'STAFF', '王俊熙', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(40, 1, 4, 'STAFF', '张晨曦', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(41, 1, 4, 'STAFF', '刘思琪', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(42, 1, 4, 'STAFF', '黄子轩', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(43, 1, 4, 'STAFF', '吴嘉怡', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(44, 1, 4, 'STAFF', '周浩宇', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(45, 1, 4, 'STAFF', '徐梦瑶', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(46, 1, 4, 'STAFF', '孙嘉豪', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(47, 1, 4, 'STAFF', '马雨欣', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(48, 1, 4, 'STAFF', '朱子涵', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(49, 1, 5, 'DEPT_HEAD', '马嘉诚', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(50, 1, 5, 'SUB_DEPT_HEAD', '罗诗涵', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(51, 1, 5, 'SUB_DEPT_HEAD', '高子豪', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(52, 1, 5, 'SUB_DEPT_HEAD', '谢婉婷', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(53, 1, 5, 'STAFF', '韩雨桐', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(54, 1, 5, 'STAFF', '曹俊杰', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(55, 1, 5, 'STAFF', '邓紫琪', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(56, 1, 5, 'STAFF', '许志强', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(57, 1, 5, 'STAFF', '傅晓彤', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(58, 1, 5, 'STAFF', '沈佳豪', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(59, 1, 5, 'STAFF', '曾雅琳', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(60, 1, 5, 'STAFF', '彭浩然', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(61, 1, 5, 'STAFF', '吕思妍', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(62, 1, 5, 'STAFF', '苏俊宇', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(63, 1, 5, 'STAFF', '蒋若曦', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(64, 1, 5, 'STAFF', '蔡明轩', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(65, 1, 5, 'STAFF', '潘雨欣', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(66, 1, 5, 'STAFF', '杜嘉诚', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(67, 1, 5, 'STAFF', '戴诗涵', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1),
(68, 1, 5, 'STAFF', '姚子健', NULL, NULL, NULL, NULL, NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1);

-- 8. 自增起点
ALTER TABLE organization AUTO_INCREMENT = 2;
ALTER TABLE sys_role AUTO_INCREMENT = 8;
ALTER TABLE role_revision AUTO_INCREMENT = 8;
ALTER TABLE sys_department AUTO_INCREMENT = 6;
ALTER TABLE sys_user AUTO_INCREMENT = 71;
ALTER TABLE org_position AUTO_INCREMENT = 13;
ALTER TABLE member_session AUTO_INCREMENT = 2;
ALTER TABLE member AUTO_INCREMENT = 69;

SET FOREIGN_KEY_CHECKS = 1;

-- 校验输出
SELECT 'organization' AS item, COUNT(*) AS n FROM organization
UNION ALL SELECT 'departments', COUNT(*) FROM sys_department
UNION ALL SELECT 'users', COUNT(*) FROM sys_user
UNION ALL SELECT 'positions', COUNT(*) FROM org_position
UNION ALL SELECT 'members', COUNT(*) FROM member
UNION ALL SELECT 'roles', COUNT(*) FROM sys_role
UNION ALL SELECT 'role_grants', COUNT(*) FROM role_grant;
