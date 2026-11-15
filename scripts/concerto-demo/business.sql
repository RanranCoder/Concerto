SET NAMES utf8mb4;
START TRANSACTION;
SET @org = 1;
SET @user = (SELECT id FROM sys_user WHERE org_id=@org AND deleted=0 ORDER BY id LIMIT 1);
INSERT INTO activity (org_id,name,theme,type,status,start_date,end_date,location,organizer,target_audience,host,leader,description,created_by) VALUES
(@org,'示例｜党员先锋志愿服务日','践行初心，服务校园','OTHER','FINISHED',DATE_SUB(CURRENT_DATE, INTERVAL 28 DAY),DATE_SUB(CURRENT_DATE, INTERVAL 28 DAY),'学院广场','协奏演示组织','党员与入党积极分子','陈知远','许清禾','演示数据：开展校园清洁、便民服务与志愿分享。',@user),
(@org,'示例｜青年理论学习交流会','学思践悟，挺膺担当','OTHER','EXECUTING',DATE_SUB(CURRENT_DATE, INTERVAL 7 DAY),DATE_SUB(CURRENT_DATE, INTERVAL 7 DAY),'学院报告厅','协奏演示组织','全体学生骨干','林若宁','许清禾','演示数据：围绕青年责任开展专题学习与交流。',@user),
(@org,'示例｜党建知识竞赛','以赛促学，凝心聚力','OTHER','PLANNING',DATE_ADD(CURRENT_DATE, INTERVAL 7 DAY),DATE_ADD(CURRENT_DATE, INTERVAL 7 DAY),'学术报告厅','协奏演示组织','各支部代表队','周明轩','许清禾','演示数据：通过知识竞答和团队展示深化理论学习。',@user);
SET @first = LAST_INSERT_ID();
INSERT INTO activity_plan (org_id,activity_id,version,background,purpose,content,flow,notice,emergency,budget,status,submitter_id,owner_id)
SELECT @org,id,1,'为加强组织建设与实践育人，结合学院工作安排开展本次活动。','促进理论学习、组织协作与服务意识。','由协奏演示组织统筹，各部门分工协作，邀请师生代表参与。','前期宣传及报名；会场布置与签到；主题环节；总结与宣传。','参与人员提前十分钟到场，遵守现场秩序。','遇天气或设备故障时启用备用场地和设备，负责人统一协调。','物料与场地费用预计 500 元，按实际报销。',IF(status='PLANNING','DRAFT','APPROVED'),@user,@user FROM activity WHERE id BETWEEN @first AND @first+2 AND org_id=@org;
INSERT INTO activity_agenda (org_id,activity_id,step_no,title,remark) SELECT @org,id,1,'入场签到','提前十分钟到场' FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO activity_agenda (org_id,activity_id,step_no,title,remark) SELECT @org,id,2,'开场与主题介绍','主持人介绍活动安排' FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO activity_agenda (org_id,activity_id,step_no,title,remark) SELECT @org,id,3,'主题活动与交流','按现场安排进行' FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO activity_agenda (org_id,activity_id,step_no,title,remark) SELECT @org,id,4,'总结合影','整理材料并有序离场' FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO task (org_id,activity_id,name,assignee_user_id,assignee,start_date,end_date,progress,status,priority,description) SELECT @org,id,'策划与方案审核',@user,'主任',DATE_SUB(start_date,INTERVAL 14 DAY),DATE_SUB(start_date,INTERVAL 7 DAY),100,'DONE',1,'完成策划书并确认组织分工。' FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO task (org_id,activity_id,name,assignee_user_id,assignee,start_date,end_date,progress,status,priority,description) SELECT @org,id,'宣传与报名',@user,'主任',DATE_SUB(start_date,INTERVAL 7 DAY),DATE_SUB(start_date,INTERVAL 1 DAY),IF(status='FINISHED',100,50),IF(status='FINISHED','DONE','DOING'),1,'发布预告，收集报名信息。' FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO task (org_id,activity_id,name,assignee_user_id,assignee,start_date,end_date,progress,status,priority,description) SELECT @org,id,'会场布置与签到',@user,'主任',start_date,start_date,IF(status='FINISHED',100,0),IF(status='FINISHED','DONE','TODO'),1,'检查设备、布置座位、组织签到。' FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO seat_map (org_id,activity_id,room_name,zone,row_no,col_no,person_name,seat_type) SELECT @org,id,location,'嘉宾区',1,1,'许清禾','嘉宾' FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO seat_map (org_id,activity_id,room_name,zone,row_no,col_no,person_name,seat_type) SELECT @org,id,location,'工作人员区',2,1,'陈知远','工作人员' FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO seat_layout (org_id,activity_id,name,`rows`,cols,aisle_cols,seat_data,created_by) SELECT @org,id,'示例会场座位布局',6,8,'[4]','[]',@user FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO score_rule (org_id,activity_id,dimension_name,full_marks,sort_order) VALUES (@org,@first+2,'理论知识',40,1),(@org,@first+2,'团队协作',30,2),(@org,@first+2,'现场表现',30,3);
SET @theory_rule = CAST((SELECT id FROM score_rule WHERE org_id=@org AND activity_id=@first+2 AND sort_order=1) AS CHAR);
SET @collaboration_rule = CAST((SELECT id FROM score_rule WHERE org_id=@org AND activity_id=@first+2 AND sort_order=2) AS CHAR);
SET @performance_rule = CAST((SELECT id FROM score_rule WHERE org_id=@org AND activity_id=@first+2 AND sort_order=3) AS CHAR);
INSERT INTO score_record (org_id,activity_id,team_name,group_name,dimension_scores,total,rank_no,remark) VALUES
(@org,@first+2,'第一支部代表队','示例组',JSON_OBJECT(@theory_rule,38,@collaboration_rule,27,@performance_rule,28),93,1,'示例评分'),
(@org,@first+2,'第二支部代表队','示例组',JSON_OBJECT(@theory_rule,35,@collaboration_rule,26,@performance_rule,27),88,2,'示例评分');
INSERT INTO signin (org_id,activity_id,person_id,name,student_no,sign_type,sign_time,location,remark) SELECT @org,@first,id,real_name,student_no,'MANUAL',TIMESTAMP(DATE_SUB(CURRENT_DATE, INTERVAL 28 DAY), "08:45:00"),'学院广场','示例签到' FROM sys_user WHERE org_id=@org AND deleted=0 ORDER BY id LIMIT 12;
INSERT INTO article (org_id,activity_id,title,summary,content,article_type,status,author_id,publish_time) SELECT @org,id,CONCAT(name,'｜活动预告'),'活动安排与参与指引（示例）',CONCAT('<p>诚邀师生参加',name,'。活动主题：',theme,'。</p><p>地点：',location,'。请提前到场签到。</p>'),'PREHEAT','PUBLISHED',@user,NOW() FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO article (org_id,activity_id,title,summary,content,article_type,status,author_id,publish_time) VALUES (@org,@first,'党员先锋志愿服务日圆满举行','师生携手开展志愿服务（示例）','<p>师生志愿者共同参与校园服务，以实际行动践行初心使命。</p>','REPORT','PUBLISHED',@user,TIMESTAMP(DATE_SUB(CURRENT_DATE, INTERVAL 27 DAY), "10:00:00"));
INSERT INTO news (org_id,activity_id,title,subtitle,content,author_id,publish_date,status) VALUES (@org,@first,'学院开展党员先锋志愿服务日','凝聚青春力量，服务美丽校园','<p>协奏演示组织组织师生开展志愿服务。活动现场秩序井然，参与者积极协作，展现了青年担当。</p>',@user,DATE_SUB(CURRENT_DATE, INTERVAL 27 DAY),'PUBLISHED');
COMMIT;

-- 以下个人资料均为虚构演示记录。
START TRANSACTION;
UPDATE activity SET end_date=DATE_ADD(CURRENT_DATE, INTERVAL 7 DAY) WHERE id=@first+1;
UPDATE task SET progress=CASE WHEN activity_id=@first THEN 100 WHEN activity_id=@first+1 THEN 50 ELSE 0 END, status=CASE WHEN activity_id=@first THEN 'DONE' WHEN activity_id=@first+1 THEN 'DOING' ELSE 'TODO' END WHERE activity_id BETWEEN @first AND @first+2 AND name IN ('宣传与报名','会场布置与签到');
UPDATE task SET end_date=DATE_ADD(CURRENT_DATE,INTERVAL 2 DAY) WHERE activity_id=@first+1 AND name IN ('宣传与报名','会场布置与签到');
UPDATE task SET assignee_user_id=2,assignee='林若宁' WHERE org_id=1 AND activity_id BETWEEN @first AND @first+2 AND name='宣传与报名';
UPDATE task SET assignee_user_id=3,assignee='周明轩' WHERE org_id=1 AND activity_id BETWEEN @first AND @first+2 AND name='会场布置与签到';
INSERT INTO schedule (org_id,schedule_type,activity_id,week_no,weekday,session_name,location,schedule_date,notes,start_time,end_time,status,source,semester,dept_id,dept_name) VALUES
(1,'ACTIVITY',@first,7,6,'上午场','学院广场',DATE_SUB(CURRENT_DATE, INTERVAL 28 DAY),'现场签到与志愿服务','08:30','11:30','ENDED','MANUAL','2026-2027-1',3,'组织部'),
(1,'ACTIVITY',@first+2,12,6,'下午场','学术报告厅',DATE_ADD(CURRENT_DATE, INTERVAL 7 DAY),'竞赛现场服务','13:30','16:30','PUBLISHED','MANUAL','2026-2027-1',3,'组织部'),
(1,'ARCHIVE',NULL,11,1,'下午值班','组织办公室',DATE_ADD(CURRENT_DATE, INTERVAL 2 DAY),'材料整理及归档','14:00','17:00','PUBLISHED','MANUAL','2026-2027-1',4,'秘书处');
SET @schedule_first=LAST_INSERT_ID();
UPDATE schedule SET weekday=MOD(DAYOFWEEK(schedule_date)+5,7)+1 WHERE id BETWEEN @schedule_first AND @schedule_first+2;
INSERT INTO schedule_person (org_id,schedule_id,user_id,person_name,is_primary) SELECT 1,@schedule_first,id,real_name,IF(id=3,1,0) FROM sys_user WHERE id IN (3,7,8,9);
INSERT INTO schedule_person (org_id,schedule_id,user_id,person_name,is_primary) SELECT 1,@schedule_first+1,id,real_name,IF(id=3,1,0) FROM sys_user WHERE id IN (3,10,11,12);
INSERT INTO schedule_person (org_id,schedule_id,user_id,person_name,is_primary) SELECT 1,@schedule_first+2,id,real_name,IF(id=4,1,0) FROM sys_user WHERE id IN (4,13,14);
INSERT INTO attendance (org_id,schedule_id,person_id,person_name,status,remark,record_time,student_no,status_source,checked_in_at) SELECT 1,@schedule_first,id,real_name,IF(id=9,'LATE','NORMAL'),'演示考勤记录',TIMESTAMP(DATE_SUB(CURRENT_DATE, INTERVAL 28 DAY), "08:40:00"),student_no,'AUTO',IF(id=9,TIMESTAMP(DATE_SUB(CURRENT_DATE, INTERVAL 28 DAY), "08:40:00"),TIMESTAMP(DATE_SUB(CURRENT_DATE, INTERVAL 28 DAY), "08:20:00")) FROM sys_user WHERE id IN (3,7,8,9);
INSERT INTO course_schedule (org_id,user_id,semester,day_of_week,period,course_name,created_at,updated_at) SELECT 1,id,'2026-2027-1',1,1,'大学英语',NOW(),NOW() FROM sys_user WHERE id BETWEEN 5 AND 16;
INSERT INTO course_schedule (org_id,user_id,semester,day_of_week,period,course_name,created_at,updated_at) SELECT 1,id,'2026-2027-1',3,3,'综合实践',NOW(),NOW() FROM sys_user WHERE id BETWEEN 5 AND 16;
INSERT INTO party_member (org_id,name,gender,nation,birth_date,native_place,education,phone,home_address,class_name,college,branch_name,political_status,student_no,remark) SELECT 1,real_name,'女','汉族','2005-05-10','示例市','本科在读','13800000000','示例市示例区','示例班级','示例学院','学生第一党支部',CASE WHEN id<9 THEN '入党积极分子' ELSE '预备党员' END,student_no,'虚构演示资料' FROM sys_user WHERE id BETWEEN 5 AND 12;
SET @party_first=LAST_INSERT_ID();
UPDATE party_member p JOIN member m ON m.org_id=p.org_id AND m.student_no=p.student_no SET p.gender=m.gender WHERE p.id BETWEEN @party_first AND @party_first+7;
UPDATE member m JOIN party_member p ON p.org_id=m.org_id AND p.student_no=m.student_no SET m.political_status=p.political_status WHERE p.id BETWEEN @party_first AND @party_first+7;
INSERT INTO party_stage (org_id,member_id,stage,issue_no,status,start_date,remark) SELECT 1,id,IF(political_status='预备党员','PROBATIONARY','ACTIVE'),'第42期','CURRENT','2026-03-01','示例培养记录' FROM party_member WHERE id BETWEEN @party_first AND @party_first+7;
INSERT INTO party_roster (org_id,roster_type,issue_no,name,gender,student_no,class_name,branch_name,remark) SELECT 1,'DEVELOPMENT','第42期',name,gender,student_no,class_name,branch_name,'演示名册' FROM party_member WHERE id BETWEEN @party_first AND @party_first+7;
INSERT INTO party_investigation (org_id,member_id,father_name,father_branch,father_branch_addr,mother_name,mother_branch,mother_branch_addr) SELECT 1,id,'示例父亲','示例支部','示例市','示例母亲','示例支部','示例市' FROM party_member WHERE id BETWEEN @party_first AND @party_first+7;
INSERT INTO party_register (org_id,member_id,college,branch,class_name,name,gender,birth_date,native_place,nation,phone,home_address,apply_date,education,talk_person,condition_note,remark) SELECT 1,id,college,branch_name,class_name,name,gender,birth_date,native_place,nation,phone,home_address,'2025-09-15',education,'示例谈话人','按计划参加学习和实践','虚构示例' FROM party_member WHERE id BETWEEN @party_first AND @party_first+7;
INSERT INTO party_transfer (org_id,member_id,class_name,name,gender,nation,is_probationary,receive_org,phone,wechat,is_online,sign_date,remark) SELECT 1,id,class_name,name,gender,nation,1,'示例市学生党支部',phone,'demo_contact',1,'2026-10-20','虚构转接示例' FROM party_member WHERE id=@party_first+7;
INSERT INTO kb_document (org_id,title,doc_type,category,body,summary,version,effective_from,owner_dept_id,visibility,status,created_by) VALUES
(1,'活动组织工作指引','MANUAL','活动管理','<p>活动由组织部牵头，提前两周制定策划，宣传部负责宣传，秘书处负责资料归档。活动结束后五个工作日内提交总结。</p>','活动策划、宣传与归档流程','演示版 1.0','2026-09-01',3,'ALL','PUBLISHED',1),
(1,'协奏演示组织会议制度','REGULATION','组织管理','<p>每月召开一次例会，各部门汇报进展与下月计划。会议纪要由秘书处整理并发布。</p>','例会和纪要的基本要求','演示版 1.0','2026-09-01',4,'ALL','PUBLISHED',1),
(1,'宣传稿件审核说明','FAQ','宣传工作','<p>稿件提交后由宣传部初审，负责人复核后发布；涉及个人信息时需获得授权。</p>','稿件审核与发布','演示版 1.0','2026-09-01',2,'ALL','PUBLISHED',1);
INSERT INTO kb_chunk (org_id,source_type,source_id,source_key,seq,title,content,token_len,tier,owner_dept_id,visibility,sensitivity) SELECT org_id,'manual',id,CONCAT('manual:',id),0,title,REPLACE(REPLACE(body,'<p>',''),'</p>',''),CHAR_LENGTH(body),'T1',owner_dept_id,visibility,sensitivity FROM kb_document WHERE org_id=1 AND title IN ('活动组织工作指引','协奏演示组织会议制度','宣传稿件审核说明');
INSERT INTO group_chat_category (org_id,name,sort_order) VALUES (1,'组织工作',1),(1,'活动协作',2);
SET @group_category=LAST_INSERT_ID();
INSERT INTO group_chat (org_id,name,category_id,activity_id,owner_id,remark,status,created_by) VALUES (1,'协奏演示组织工作群',@group_category,NULL,1,'组织日常沟通示例','ACTIVE',1),(1,'党建知识竞赛筹备群',@group_category+1,@first+2,3,'活动筹备与分工示例','ACTIVE',1);
SET @group_first=LAST_INSERT_ID();
INSERT INTO group_chat_department (org_id,group_chat_id,department) VALUES (1,@group_first,'主任'),(1,@group_first,'宣传部'),(1,@group_first,'组织部'),(1,@group_first,'秘书处'),(1,@group_first+1,'组织部'),(1,@group_first+1,'宣传部');
INSERT INTO announcement (org_id,title,content,publisher_id,publish_time,important,require_confirmation) VALUES (1,'关于召开十一月组织例会的通知','<p>请各部门提前整理工作进展，于下周一下午参加组织例会。</p>',1,'2026-11-12 09:00:00',1,1),(1,'党建知识竞赛筹备安排','<p>请宣传部、组织部和秘书处按照任务清单完成活动准备。</p>',1,'2026-11-10 10:00:00',0,0);
SET @announcement_first=LAST_INSERT_ID();
INSERT INTO announcement_read (org_id,announcement_id,user_id,read_at) SELECT 1,@announcement_first,id,'2026-11-12 12:00:00' FROM sys_user WHERE id BETWEEN 1 AND 8;
INSERT INTO announcement_receipt (org_id,announcement_id,user_id,version,confirmed_at) SELECT 1,@announcement_first,id,1,'2026-11-12 12:10:00' FROM sys_user WHERE id BETWEEN 1 AND 6;
INSERT INTO sign_in_group (org_id,activity_id,group_name,source_filename,sort_order) VALUES (1,@first,'志愿服务参与名单',NULL,1),(1,@first+2,'竞赛工作人员',NULL,1);
SET @roster_group=LAST_INSERT_ID();
INSERT INTO signin_field_config (org_id,activity_id,field_name,field_key,required,field_type,sort_order) VALUES (1,@first,'姓名','name',1,'TEXT',1),(1,@first,'学号','studentNo',1,'TEXT',2),(1,@first+2,'姓名','name',1,'TEXT',1),(1,@first+2,'学号','studentNo',1,'TEXT',2);
INSERT INTO signin_roster (org_id,activity_id,group_id,fields_json) SELECT 1,@first,@roster_group,JSON_OBJECT('name',real_name,'studentNo',student_no) FROM sys_user WHERE id BETWEEN 1 AND 12;
INSERT INTO signin_roster (org_id,activity_id,group_id,fields_json) SELECT 1,@first+2,@roster_group+1,JSON_OBJECT('name',real_name,'studentNo',student_no) FROM sys_user WHERE id BETWEEN 3 AND 12;
INSERT INTO material (org_id,name,biz_type,activity_id,dept_id,uploader_id,tag,description) VALUES (1,'志愿服务活动总结','OTHER',@first,4,4,'总结,演示','演示资料条目，尚未上传附件'),(1,'知识竞赛筹备资料','OTHER',@first+2,3,3,'筹备,演示','演示资料条目，尚未上传附件');
INSERT INTO template_asset (org_id,name,category,description,created_by) VALUES (1,'活动策划书模板','PLAN','活动背景、目标、流程和预算的演示模板条目',1),(1,'会议签到模板','SIGNIN','会议签到的演示模板条目',4);
INSERT INTO credit_record (org_id,user_id,person_name,student_no,activity_id,project,credit,basis,remark,record_by) SELECT 1,id,real_name,student_no,@first,'志愿服务活动',0.50,'PARTICIPATE','演示加分记录',1 FROM sys_user WHERE id BETWEEN 5 AND 12;
COMMIT;
