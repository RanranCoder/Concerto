-- 仅在新建演示库、后端完成首次初始化后执行。
USE pams_concerto_demo;
SET FOREIGN_KEY_CHECKS=0;
DELETE FROM activity_agenda;
DELETE FROM activity_plan;
DELETE FROM task;
DELETE FROM signin;
DELETE FROM article;
DELETE FROM news;
DELETE FROM material;
DELETE FROM announcement;
DELETE FROM party_stage;
DELETE FROM party_member;
DELETE FROM credit_record;
DELETE FROM activity;
SET FOREIGN_KEY_CHECKS=1;
