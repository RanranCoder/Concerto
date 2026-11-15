-- 虚构的演示通知及对应的个人收件箱。
SET NAMES utf8mb4;
START TRANSACTION;
INSERT INTO notification (id, org_id, type, title, content, entity_type, entity_id, activity_id, sender_id, recipient_id, is_read, read_at, created_at, event_key, deleted)
SELECT 1001, 1, 'TASK_ASSIGNED', '演示任务：请完成活动宣传', '请查看活动任务安排，准备预告文案和宣传物料。', 'ACTIVITY', id, id, 1, 1, 0, NULL, NOW(), 'demo-task-1001', 0 FROM activity WHERE org_id=1 AND deleted=0 ORDER BY id DESC LIMIT 1;
INSERT INTO notification (id, org_id, type, title, content, entity_type, entity_id, activity_id, sender_id, recipient_id, is_read, read_at, created_at, event_key, deleted)
SELECT 1002, 1, 'PLAN_APPROVED', '演示通知：活动方案已通过', '活动方案已审核，请按计划推进各部门准备工作。', 'ACTIVITY', id, id, 1, 1, 1, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), 'demo-plan-1002', 0 FROM activity WHERE org_id=1 AND deleted=0 ORDER BY id LIMIT 1;
INSERT INTO notification_inbox (org_id,message_id,user_id,read_at,created_at)
SELECT org_id,id,recipient_id,read_at,created_at FROM notification WHERE org_id=1 AND id IN (1001,1002);
COMMIT;
