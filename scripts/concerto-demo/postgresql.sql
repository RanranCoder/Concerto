-- 原 MySQL 演示数据的 PostgreSQL 版本。仅在独立空库完成 V75 和基础字典初始化后使用。
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL search_path TO pams;
SET LOCAL client_encoding TO 'UTF8';
SET LOCAL TIME ZONE 'Asia/Shanghai';

DO $$
BEGIN
    IF current_database() <> 'pams_concerto_demo' OR
       NOT EXISTS (SELECT 1 FROM pams_flyway.bootstrap_mode WHERE mode = 'empty-seed') OR
       EXISTS (SELECT 1 FROM organization WHERE id <> 1) OR
       EXISTS (SELECT 1 FROM sys_user) OR
       EXISTS (SELECT 1 FROM member) OR
       EXISTS (SELECT 1 FROM activity) THEN
        RAISE EXCEPTION 'A newly initialized pams_concerto_demo database is required';
    END IF;
END
$$;

DELETE FROM member_session;
DELETE FROM org_position;
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

\set org 1
SELECT id AS actor FROM sys_user WHERE org_id=:org AND deleted=0 ORDER BY id LIMIT 1 \gset
INSERT INTO activity (org_id,name,theme,type,status,start_date,end_date,location,organizer,target_audience,host,leader,description,created_by) VALUES
(:org,'示例｜党员先锋志愿服务日','践行初心，服务校园','OTHER','FINISHED',(CURRENT_DATE - 28),(CURRENT_DATE - 28),'学院广场','协奏演示组织','党员与入党积极分子','陈知远','许清禾','演示数据：开展校园清洁、便民服务与志愿分享。',:actor),
(:org,'示例｜青年理论学习交流会','学思践悟，挺膺担当','OTHER','EXECUTING',(CURRENT_DATE - 7),(CURRENT_DATE - 7),'学院报告厅','协奏演示组织','全体学生骨干','林若宁','许清禾','演示数据：围绕青年责任开展专题学习与交流。',:actor),
(:org,'示例｜党建知识竞赛','以赛促学，凝心聚力','OTHER','PLANNING',(CURRENT_DATE + 7),(CURRENT_DATE + 7),'学术报告厅','协奏演示组织','各支部代表队','周明轩','许清禾','演示数据：通过知识竞答和团队展示深化理论学习。',:actor);
SELECT currval(pg_get_serial_sequence('pams.activity', 'id')) - 2 AS first \gset
INSERT INTO activity_plan (org_id,activity_id,version,background,purpose,content,flow,notice,emergency,budget,status,submitter_id,owner_id)
SELECT :org,id,1,'为加强组织建设与实践育人，结合学院工作安排开展本次活动。','促进理论学习、组织协作与服务意识。','由协奏演示组织统筹，各部门分工协作，邀请师生代表参与。','前期宣传及报名；会场布置与签到；主题环节；总结与宣传。','参与人员提前十分钟到场，遵守现场秩序。','遇天气或设备故障时启用备用场地和设备，负责人统一协调。','物料与场地费用预计 500 元，按实际报销。',CASE WHEN status='PLANNING' THEN 'DRAFT' ELSE 'APPROVED' END,:actor,:actor FROM activity WHERE id BETWEEN :first AND :first+2 AND org_id=:org;
INSERT INTO activity_agenda (org_id,activity_id,step_no,title,remark) SELECT :org,id,1,'入场签到','提前十分钟到场' FROM activity WHERE id BETWEEN :first AND :first+2;
INSERT INTO activity_agenda (org_id,activity_id,step_no,title,remark) SELECT :org,id,2,'开场与主题介绍','主持人介绍活动安排' FROM activity WHERE id BETWEEN :first AND :first+2;
INSERT INTO activity_agenda (org_id,activity_id,step_no,title,remark) SELECT :org,id,3,'主题活动与交流','按现场安排进行' FROM activity WHERE id BETWEEN :first AND :first+2;
INSERT INTO activity_agenda (org_id,activity_id,step_no,title,remark) SELECT :org,id,4,'总结合影','整理材料并有序离场' FROM activity WHERE id BETWEEN :first AND :first+2;
INSERT INTO task (org_id,activity_id,name,assignee_user_id,assignee,start_date,end_date,progress,status,priority,description) SELECT :org,id,'策划与方案审核',:actor,'主任',(start_date - 14),(start_date - 7),100,'DONE',1,'完成策划书并确认组织分工。' FROM activity WHERE id BETWEEN :first AND :first+2;
INSERT INTO task (org_id,activity_id,name,assignee_user_id,assignee,start_date,end_date,progress,status,priority,description) SELECT :org,id,'宣传与报名',:actor,'主任',(start_date - 7),(start_date - 1),CASE WHEN status='FINISHED' THEN 100 ELSE 50 END,CASE WHEN status='FINISHED' THEN 'DONE' ELSE 'DOING' END,1,'发布预告，收集报名信息。' FROM activity WHERE id BETWEEN :first AND :first+2;
INSERT INTO task (org_id,activity_id,name,assignee_user_id,assignee,start_date,end_date,progress,status,priority,description) SELECT :org,id,'会场布置与签到',:actor,'主任',start_date,start_date,CASE WHEN status='FINISHED' THEN 100 ELSE 0 END,CASE WHEN status='FINISHED' THEN 'DONE' ELSE 'TODO' END,1,'检查设备、布置座位、组织签到。' FROM activity WHERE id BETWEEN :first AND :first+2;
INSERT INTO seat_map (org_id,activity_id,room_name,zone,row_no,col_no,person_name,seat_type) SELECT :org,id,location,'嘉宾区',1,1,'许清禾','嘉宾' FROM activity WHERE id BETWEEN :first AND :first+2;
INSERT INTO seat_map (org_id,activity_id,room_name,zone,row_no,col_no,person_name,seat_type) SELECT :org,id,location,'工作人员区',2,1,'陈知远','工作人员' FROM activity WHERE id BETWEEN :first AND :first+2;
INSERT INTO seat_layout (org_id,activity_id,name,rows,cols,aisle_cols,seat_data,created_by) SELECT :org,id,'示例会场座位布局',6,8,'[4]','[]',:actor FROM activity WHERE id BETWEEN :first AND :first+2;
INSERT INTO score_rule (org_id,activity_id,dimension_name,full_marks,sort_order) VALUES (:org,:first+2,'理论知识',40,1),(:org,:first+2,'团队协作',30,2),(:org,:first+2,'现场表现',30,3);
SELECT id AS theory_rule FROM score_rule WHERE org_id=:org AND activity_id=:first+2 AND sort_order=1 \gset
SELECT id AS collaboration_rule FROM score_rule WHERE org_id=:org AND activity_id=:first+2 AND sort_order=2 \gset
SELECT id AS performance_rule FROM score_rule WHERE org_id=:org AND activity_id=:first+2 AND sort_order=3 \gset
INSERT INTO score_record (org_id,activity_id,team_name,group_name,dimension_scores,total,rank_no,remark) VALUES
(:org,:first+2,'第一支部代表队','示例组',jsonb_build_object(CAST(:theory_rule AS text),38,CAST(:collaboration_rule AS text),27,CAST(:performance_rule AS text),28),93,1,'示例评分'),
(:org,:first+2,'第二支部代表队','示例组',jsonb_build_object(CAST(:theory_rule AS text),35,CAST(:collaboration_rule AS text),26,CAST(:performance_rule AS text),27),88,2,'示例评分');
INSERT INTO signin (org_id,activity_id,person_id,name,student_no,sign_type,sign_time,location,remark) SELECT :org,:first,id,real_name,student_no,'MANUAL',((CURRENT_DATE - 28) + TIME '08:45:00'),'学院广场','示例签到' FROM sys_user WHERE org_id=:org AND deleted=0 ORDER BY id LIMIT 12;
INSERT INTO article (org_id,activity_id,title,summary,content,article_type,status,author_id,publish_time) SELECT :org,id,CONCAT(name,'｜活动预告'),'活动安排与参与指引（示例）',CONCAT('<p>诚邀师生参加',name,'。活动主题：',theme,'。</p><p>地点：',location,'。请提前到场签到。</p>'),'PREHEAT','PUBLISHED',:actor,NOW() FROM activity WHERE id BETWEEN :first AND :first+2;
INSERT INTO article (org_id,activity_id,title,summary,content,article_type,status,author_id,publish_time) VALUES (:org,:first,'党员先锋志愿服务日圆满举行','师生携手开展志愿服务（示例）','<p>师生志愿者共同参与校园服务，以实际行动践行初心使命。</p>','REPORT','PUBLISHED',:actor,((CURRENT_DATE - 27) + TIME '10:00:00'));
INSERT INTO news (org_id,activity_id,title,subtitle,content,author_id,publish_date,status) VALUES (:org,:first,'学院开展党员先锋志愿服务日','凝聚青春力量，服务美丽校园','<p>协奏演示组织组织师生开展志愿服务。活动现场秩序井然，参与者积极协作，展现了青年担当。</p>',:actor,(CURRENT_DATE - 27),'PUBLISHED');

-- 以下个人资料均为虚构演示记录。
UPDATE activity SET end_date=(CURRENT_DATE + 7) WHERE id=:first+1;
UPDATE task SET progress=CASE WHEN activity_id=:first THEN 100 WHEN activity_id=:first+1 THEN 50 ELSE 0 END, status=CASE WHEN activity_id=:first THEN 'DONE' WHEN activity_id=:first+1 THEN 'DOING' ELSE 'TODO' END WHERE activity_id BETWEEN :first AND :first+2 AND name IN ('宣传与报名','会场布置与签到');
UPDATE task SET end_date=(CURRENT_DATE + 2) WHERE activity_id=:first+1 AND name IN ('宣传与报名','会场布置与签到');
UPDATE task SET assignee_user_id=2,assignee='林若宁' WHERE org_id=1 AND activity_id BETWEEN :first AND :first+2 AND name='宣传与报名';
UPDATE task SET assignee_user_id=3,assignee='周明轩' WHERE org_id=1 AND activity_id BETWEEN :first AND :first+2 AND name='会场布置与签到';
INSERT INTO schedule (org_id,schedule_type,activity_id,week_no,weekday,session_name,location,schedule_date,notes,start_time,end_time,status,source,semester,dept_id,dept_name) VALUES
(1,'ACTIVITY',:first,7,6,'上午场','学院广场',(CURRENT_DATE - 28),'现场签到与志愿服务','08:30','11:30','ENDED','MANUAL','2026-2027-1',3,'组织部'),
(1,'ACTIVITY',:first+2,12,6,'下午场','学术报告厅',(CURRENT_DATE + 7),'竞赛现场服务','13:30','16:30','PUBLISHED','MANUAL','2026-2027-1',3,'组织部'),
(1,'ARCHIVE',NULL,11,1,'下午值班','组织办公室',(CURRENT_DATE + 2),'材料整理及归档','14:00','17:00','PUBLISHED','MANUAL','2026-2027-1',4,'秘书处');
SELECT currval(pg_get_serial_sequence('pams.schedule', 'id')) - 2 AS schedule_first \gset
UPDATE schedule SET weekday=EXTRACT(ISODOW FROM schedule_date) WHERE id BETWEEN :schedule_first AND :schedule_first+2;
INSERT INTO schedule_person (org_id,schedule_id,user_id,person_name,is_primary) SELECT 1,:schedule_first,id,real_name,CASE WHEN id=3 THEN 1 ELSE 0 END FROM sys_user WHERE id IN (3,7,8,9);
INSERT INTO schedule_person (org_id,schedule_id,user_id,person_name,is_primary) SELECT 1,:schedule_first+1,id,real_name,CASE WHEN id=3 THEN 1 ELSE 0 END FROM sys_user WHERE id IN (3,10,11,12);
INSERT INTO schedule_person (org_id,schedule_id,user_id,person_name,is_primary) SELECT 1,:schedule_first+2,id,real_name,CASE WHEN id=4 THEN 1 ELSE 0 END FROM sys_user WHERE id IN (4,13,14);
INSERT INTO attendance (org_id,schedule_id,person_id,person_name,status,remark,record_time,student_no,status_source,checked_in_at) SELECT 1,:schedule_first,id,real_name,CASE WHEN id=9 THEN 'LATE' ELSE 'NORMAL' END,'演示考勤记录',((CURRENT_DATE - 28) + TIME '08:40:00'),student_no,'AUTO',CASE WHEN id=9 THEN ((CURRENT_DATE - 28) + TIME '08:40:00') ELSE ((CURRENT_DATE - 28) + TIME '08:20:00') END FROM sys_user WHERE id IN (3,7,8,9);
INSERT INTO course_schedule (org_id,user_id,semester,day_of_week,period,course_name,created_at,updated_at) SELECT 1,id,'2026-2027-1',1,1,'大学英语',NOW(),NOW() FROM sys_user WHERE id BETWEEN 5 AND 16;
INSERT INTO course_schedule (org_id,user_id,semester,day_of_week,period,course_name,created_at,updated_at) SELECT 1,id,'2026-2027-1',3,3,'综合实践',NOW(),NOW() FROM sys_user WHERE id BETWEEN 5 AND 16;
INSERT INTO party_member (org_id,name,gender,nation,birth_date,native_place,education,phone,home_address,class_name,college,branch_name,political_status,student_no,remark) SELECT 1,real_name,'女','汉族','2005-05-10','示例市','本科在读','13800000000','示例市示例区','示例班级','示例学院','学生第一党支部',CASE WHEN id<9 THEN '入党积极分子' ELSE '预备党员' END,student_no,'虚构演示资料' FROM sys_user WHERE id BETWEEN 5 AND 12;
SELECT currval(pg_get_serial_sequence('pams.party_member', 'id')) - 7 AS party_first \gset
UPDATE party_member p SET gender=m.gender FROM member m WHERE m.org_id=p.org_id AND m.student_no=p.student_no AND p.id BETWEEN :party_first AND :party_first+7;
UPDATE member m SET political_status=p.political_status FROM party_member p WHERE p.org_id=m.org_id AND p.student_no=m.student_no AND p.id BETWEEN :party_first AND :party_first+7;
INSERT INTO party_stage (org_id,member_id,stage,issue_no,status,start_date,remark) SELECT 1,id,CASE WHEN political_status='预备党员' THEN 'PROBATIONARY' ELSE 'ACTIVE' END,'第42期','CURRENT','2026-03-01','示例培养记录' FROM party_member WHERE id BETWEEN :party_first AND :party_first+7;
INSERT INTO party_roster (org_id,roster_type,issue_no,name,gender,student_no,class_name,branch_name,remark) SELECT 1,'DEVELOPMENT','第42期',name,gender,student_no,class_name,branch_name,'演示名册' FROM party_member WHERE id BETWEEN :party_first AND :party_first+7;
INSERT INTO party_investigation (org_id,member_id,father_name,father_branch,father_branch_addr,mother_name,mother_branch,mother_branch_addr) SELECT 1,id,'示例父亲','示例支部','示例市','示例母亲','示例支部','示例市' FROM party_member WHERE id BETWEEN :party_first AND :party_first+7;
INSERT INTO party_register (org_id,member_id,college,branch,class_name,name,gender,birth_date,native_place,nation,phone,home_address,apply_date,education,talk_person,condition_note,remark) SELECT 1,id,college,branch_name,class_name,name,gender,birth_date,native_place,nation,phone,home_address,'2025-09-15',education,'示例谈话人','按计划参加学习和实践','虚构示例' FROM party_member WHERE id BETWEEN :party_first AND :party_first+7;
INSERT INTO party_transfer (org_id,member_id,class_name,name,gender,nation,is_probationary,receive_org,phone,wechat,is_online,sign_date,remark) SELECT 1,id,class_name,name,gender,nation,1,'示例市学生党支部',phone,'demo_contact',1,'2026-10-20','虚构转接示例' FROM party_member WHERE id=:party_first+7;
INSERT INTO kb_document (org_id,title,doc_type,category,body,summary,version,effective_from,owner_dept_id,visibility,status,created_by) VALUES
(1,'活动组织工作指引','MANUAL','活动管理','<p>活动由组织部牵头，提前两周制定策划，宣传部负责宣传，秘书处负责资料归档。活动结束后五个工作日内提交总结。</p>','活动策划、宣传与归档流程','演示版 1.0','2026-09-01',3,'ALL','PUBLISHED',1),
(1,'协奏演示组织会议制度','REGULATION','组织管理','<p>每月召开一次例会，各部门汇报进展与下月计划。会议纪要由秘书处整理并发布。</p>','例会和纪要的基本要求','演示版 1.0','2026-09-01',4,'ALL','PUBLISHED',1),
(1,'宣传稿件审核说明','FAQ','宣传工作','<p>稿件提交后由宣传部初审，负责人复核后发布；涉及个人信息时需获得授权。</p>','稿件审核与发布','演示版 1.0','2026-09-01',2,'ALL','PUBLISHED',1);
INSERT INTO kb_chunk (org_id,source_type,source_id,source_key,seq,title,content,token_len,tier,owner_dept_id,visibility,sensitivity) SELECT org_id,'manual',id,CONCAT('manual:',id),0,title,REPLACE(REPLACE(body,'<p>',''),'</p>',''),CHAR_LENGTH(body),'T1',owner_dept_id,visibility,sensitivity FROM kb_document WHERE org_id=1 AND title IN ('活动组织工作指引','协奏演示组织会议制度','宣传稿件审核说明');
INSERT INTO group_chat_category (org_id,name,sort_order) VALUES (1,'组织工作',1),(1,'活动协作',2);
SELECT currval(pg_get_serial_sequence('pams.group_chat_category', 'id')) - 1 AS group_category \gset
INSERT INTO group_chat (org_id,name,category_id,activity_id,owner_id,remark,status,created_by) VALUES (1,'协奏演示组织工作群',:group_category,NULL,1,'组织日常沟通示例','ACTIVE',1),(1,'党建知识竞赛筹备群',:group_category+1,:first+2,3,'活动筹备与分工示例','ACTIVE',1);
SELECT currval(pg_get_serial_sequence('pams.group_chat', 'id')) - 1 AS group_first \gset
INSERT INTO group_chat_department (org_id,group_chat_id,department) VALUES (1,:group_first,'主任'),(1,:group_first,'宣传部'),(1,:group_first,'组织部'),(1,:group_first,'秘书处'),(1,:group_first+1,'组织部'),(1,:group_first+1,'宣传部');
INSERT INTO announcement (org_id,title,content,publisher_id,publish_time,important,require_confirmation) VALUES (1,'关于召开十一月组织例会的通知','<p>请各部门提前整理工作进展，于下周一下午参加组织例会。</p>',1,'2026-11-12 09:00:00',true,true),(1,'党建知识竞赛筹备安排','<p>请宣传部、组织部和秘书处按照任务清单完成活动准备。</p>',1,'2026-11-10 10:00:00',false,false);
SELECT currval(pg_get_serial_sequence('pams.announcement', 'id')) - 1 AS announcement_first \gset
INSERT INTO announcement_read (org_id,announcement_id,user_id,read_at) SELECT 1,:announcement_first,id,'2026-11-12 12:00:00' FROM sys_user WHERE id BETWEEN 1 AND 8;
INSERT INTO announcement_receipt (org_id,announcement_id,user_id,version,confirmed_at) SELECT 1,:announcement_first,id,1,'2026-11-12 12:10:00' FROM sys_user WHERE id BETWEEN 1 AND 6;
INSERT INTO sign_in_group (org_id,activity_id,group_name,source_filename,sort_order) VALUES (1,:first,'志愿服务参与名单',NULL,1),(1,:first+2,'竞赛工作人员',NULL,1);
SELECT currval(pg_get_serial_sequence('pams.sign_in_group', 'id')) - 1 AS roster_group \gset
INSERT INTO signin_field_config (org_id,activity_id,field_name,field_key,required,field_type,sort_order) VALUES (1,:first,'姓名','name',1,'TEXT',1),(1,:first,'学号','studentNo',1,'TEXT',2),(1,:first+2,'姓名','name',1,'TEXT',1),(1,:first+2,'学号','studentNo',1,'TEXT',2);
INSERT INTO signin_roster (org_id,activity_id,group_id,fields_json) SELECT 1,:first,:roster_group,jsonb_build_object('name',real_name,'studentNo',student_no) FROM sys_user WHERE id BETWEEN 1 AND 12;
INSERT INTO signin_roster (org_id,activity_id,group_id,fields_json) SELECT 1,:first+2,:roster_group+1,jsonb_build_object('name',real_name,'studentNo',student_no) FROM sys_user WHERE id BETWEEN 3 AND 12;
INSERT INTO material (org_id,name,biz_type,activity_id,dept_id,uploader_id,tag,description) VALUES (1,'志愿服务活动总结','OTHER',:first,4,4,'总结,演示','演示资料条目，尚未上传附件'),(1,'知识竞赛筹备资料','OTHER',:first+2,3,3,'筹备,演示','演示资料条目，尚未上传附件');
INSERT INTO template_asset (org_id,name,category,description,created_by) VALUES (1,'活动策划书模板','PLAN','活动背景、目标、流程和预算的演示模板条目',1),(1,'会议签到模板','SIGNIN','会议签到的演示模板条目',4);
INSERT INTO credit_record (org_id,user_id,person_name,student_no,activity_id,project,credit,basis,remark,record_by) SELECT 1,id,real_name,student_no,:first,'志愿服务活动',0.50,'PARTICIPATE','演示加分记录',1 FROM sys_user WHERE id BETWEEN 5 AND 12;

-- 虚构的演示通知及对应的个人收件箱。
INSERT INTO notification (id, org_id, type, title, content, entity_type, entity_id, activity_id, sender_id, recipient_id, is_read, read_at, created_at, event_key, deleted)
SELECT 1001, 1, 'TASK_ASSIGNED', '演示任务：请完成活动宣传', '请查看活动任务安排，准备预告文案和宣传物料。', 'ACTIVITY', id, id, 1, 1, 0, NULL, NOW(), 'demo-task-1001', 0 FROM activity WHERE org_id=1 AND deleted=0 ORDER BY id DESC LIMIT 1;
INSERT INTO notification (id, org_id, type, title, content, entity_type, entity_id, activity_id, sender_id, recipient_id, is_read, read_at, created_at, event_key, deleted)
SELECT 1002, 1, 'PLAN_APPROVED', '演示通知：活动方案已通过', '活动方案已审核，请按计划推进各部门准备工作。', 'ACTIVITY', id, id, 1, 1, 1, (NOW() - INTERVAL '1 days'), (NOW() - INTERVAL '2 days'), 'demo-plan-1002', 0 FROM activity WHERE org_id=1 AND deleted=0 ORDER BY id LIMIT 1;
INSERT INTO notification_inbox (org_id,message_id,user_id,read_at,created_at)
SELECT org_id,id,recipient_id,read_at,created_at FROM notification WHERE org_id=1 AND id IN (1001,1002);

-- 保留演示账号及通知 ID 后校准标识列。
SELECT setval(pg_get_serial_sequence('pams.sys_department', 'id'), (SELECT MAX(id) FROM sys_department));
SELECT setval(pg_get_serial_sequence('pams.sys_user', 'id'), (SELECT MAX(id) FROM sys_user));
SELECT setval(pg_get_serial_sequence('pams.member_session', 'id'), (SELECT MAX(id) FROM member_session));
SELECT setval(pg_get_serial_sequence('pams.member', 'id'), (SELECT MAX(id) FROM member));
SELECT setval(pg_get_serial_sequence('pams.notification', 'id'), (SELECT MAX(id) FROM notification));
COMMIT;
