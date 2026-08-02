-- Task 24: 修正 V1 中 material.biz_type 注释拼写 SCHEUDLE -> SCHEDULE
-- V1 已迁移的库无法原地改注释（会破坏 Flyway 校验和），故新增 V2 用 ALTER 修正运行时库。
-- 对全新库：V1 源文件注释已同步为 SCHEDULE，本语句幂等，无副作用。
ALTER TABLE material MODIFY COLUMN biz_type VARCHAR(30) NOT NULL COMMENT 'SIGNIN/SCHEDULE/ATTENDANCE/PLAN/NEWS/ARTICLE/PHOTO/PPT/INVOICE/ROSTER/OTHER';
