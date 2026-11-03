SET NAMES utf8mb4;
START TRANSACTION;
SET @org = 1;
SET @user = (SELECT id FROM sys_user WHERE org_id=@org AND deleted=0 ORDER BY id LIMIT 1);
INSERT INTO activity (org_id,name,theme,type,status,start_date,end_date,location,organizer,target_audience,host,leader,description,created_by) VALUES
(@org,'示例｜党员先锋志愿服务日','践行初心，服务校园','OTHER','FINISHED','2026-10-17','2026-10-17','学院广场','党建办公室','党员与入党积极分子','陈志远','指导老师','演示数据：开展校园清洁、便民服务与志愿分享。',@user),
(@org,'示例｜青年理论学习交流会','学思践悟，挺膺担当','OTHER','EXECUTING','2026-11-07','2026-11-07','学院报告厅','党建办公室','全体学生骨干','李慧敏','指导老师','演示数据：围绕青年责任开展专题学习与交流。',@user),
(@org,'示例｜党建知识竞赛','以赛促学，凝心聚力','OTHER','PLANNING','2026-11-21','2026-11-21','学术报告厅','党建办公室','各支部代表队','周建国','指导老师','演示数据：通过知识竞答和团队展示深化理论学习。',@user);
SET @first = LAST_INSERT_ID();
INSERT INTO activity_plan (org_id,activity_id,version,background,purpose,content,flow,notice,emergency,budget,status,submitter_id,owner_id)
SELECT @org,id,1,'为加强组织建设与实践育人，结合学院工作安排开展本次活动。','促进理论学习、组织协作与服务意识。','由党建办公室统筹，各部门分工协作，邀请师生代表参与。','前期宣传及报名；会场布置与签到；主题环节；总结与宣传。','参与人员提前十分钟到场，遵守现场秩序。','遇天气或设备故障时启用备用场地和设备，负责人统一协调。','物料与场地费用预计 500 元，按实际报销。',IF(status='PLANNING','DRAFT','APPROVED'),@user,@user FROM activity WHERE id BETWEEN @first AND @first+2 AND org_id=@org;
INSERT INTO activity_agenda (org_id,activity_id,step_no,title,remark) SELECT @org,id,1,'入场签到','提前十分钟到场' FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO activity_agenda (org_id,activity_id,step_no,title,remark) SELECT @org,id,2,'开场与主题介绍','主持人介绍活动安排' FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO activity_agenda (org_id,activity_id,step_no,title,remark) SELECT @org,id,3,'主题活动与交流','按现场安排进行' FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO activity_agenda (org_id,activity_id,step_no,title,remark) SELECT @org,id,4,'总结合影','整理材料并有序离场' FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO task (org_id,activity_id,name,assignee_user_id,assignee,start_date,end_date,progress,status,priority,description) SELECT @org,id,'策划与方案审核',@user,'系统管理员',DATE_SUB(start_date,INTERVAL 14 DAY),DATE_SUB(start_date,INTERVAL 7 DAY),100,'DONE',1,'完成策划书并确认组织分工。' FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO task (org_id,activity_id,name,assignee_user_id,assignee,start_date,end_date,progress,status,priority,description) SELECT @org,id,'宣传与报名',@user,'系统管理员',DATE_SUB(start_date,INTERVAL 7 DAY),DATE_SUB(start_date,INTERVAL 1 DAY),IF(status='FINISHED',100,50),IF(status='FINISHED','DONE','DOING'),1,'发布预告，收集报名信息。' FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO task (org_id,activity_id,name,assignee_user_id,assignee,start_date,end_date,progress,status,priority,description) SELECT @org,id,'会场布置与签到',@user,'系统管理员',start_date,start_date,IF(status='FINISHED',100,0),IF(status='FINISHED','DONE','TODO'),1,'检查设备、布置座位、组织签到。' FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO seat_map (org_id,activity_id,room_name,zone,row_no,col_no,person_name,seat_type) SELECT @org,id,location,'嘉宾区',1,1,'指导老师','嘉宾' FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO seat_map (org_id,activity_id,room_name,zone,row_no,col_no,person_name,seat_type) SELECT @org,id,location,'工作人员区',2,1,'陈志远','工作人员' FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO seat_layout (org_id,activity_id,name,`rows`,cols,aisle_cols,seat_data,created_by) SELECT @org,id,'示例会场座位布局',6,8,'[4]','[]',@user FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO score_rule (org_id,activity_id,dimension_name,full_marks,sort_order) VALUES (@org,@first+2,'理论知识',40,1),(@org,@first+2,'团队协作',30,2),(@org,@first+2,'现场表现',30,3);
INSERT INTO score_record (org_id,activity_id,team_name,group_name,dimension_scores,total,rank_no,remark) VALUES (@org,@first+2,'第一支部代表队','示例组','[38,27,28]',93,1,'示例评分'),(@org,@first+2,'第二支部代表队','示例组','[35,26,27]',88,2,'示例评分');
INSERT INTO signin (org_id,activity_id,person_id,name,student_no,sign_type,sign_time,location,remark) SELECT @org,@first,id,real_name,student_no,'MANUAL','2026-10-17 08:45:00','学院广场','示例签到' FROM sys_user WHERE org_id=@org AND deleted=0 ORDER BY id LIMIT 12;
INSERT INTO article (org_id,activity_id,title,summary,content,article_type,status,author_id,publish_time) SELECT @org,id,CONCAT(name,'｜活动预告'),'活动安排与参与指引（示例）',CONCAT('<p>诚邀师生参加',name,'。活动主题：',theme,'。</p><p>地点：',location,'。请提前到场签到。</p>'),'PREHEAT','PUBLISHED',@user,NOW() FROM activity WHERE id BETWEEN @first AND @first+2;
INSERT INTO article (org_id,activity_id,title,summary,content,article_type,status,author_id,publish_time) VALUES (@org,@first,'党员先锋志愿服务日圆满举行','师生携手开展志愿服务（示例）','<p>师生志愿者共同参与校园服务，以实际行动践行初心使命。</p>','REPORT','PUBLISHED',@user,'2026-10-18 10:00:00');
INSERT INTO news (org_id,activity_id,title,subtitle,content,author_id,publish_date,status) VALUES (@org,@first,'学院开展党员先锋志愿服务日','凝聚青春力量，服务美丽校园','<p>10 月 17 日，学院党建办公室组织师生开展志愿服务。活动现场秩序井然，参与者积极协作，展现了青年担当。</p>',@user,'2026-10-18','PUBLISHED');
COMMIT;
