-- ===================== V17：D2 素拓积分查询索引 + D3 核心表字符集统一 =====================
-- D2：credit_record 高频查询列补索引（CreditService / CreditRecordRepository：
--      findBySourceActivityId / findByBatchId / findByStudentNoOrderByCreatedAtDesc）。
-- D3：V1/V3/V10/V13/V15 建表未显式指定字符集，依赖数据库默认；V4~V8 起显式
--      ENGINE=InnoDB DEFAULT CHARSET=utf8mb4。docker-compose 已固定
--      --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci，
--      故统一 CONVERT TO utf8mb4 COLLATE utf8mb4_unicode_ci，与既有表一致、无乱码/排序混用问题。
--      注意：ALTER TABLE ... CONVERT TO CHARACTER SET 在 MySQL 幂等可重跑（已是目标字符集时为 no-op）；
--      H2(MySQL 模式) 解析并忽略该语句，测试兼容。

-- ---------- D2: credit_record 查询索引 ----------
-- MySQL 不支持 CREATE INDEX IF NOT EXISTS / DROP INDEX IF EXISTS（见 V15 注释先例），
-- 与 V6/V16 一致采用 plain ALTER，依赖 Flyway 单次执行；索引名唯一避免与既有索引冲突。
ALTER TABLE credit_record ADD INDEX idx_credit_record_source_batch (source_activity_id, batch_id);
ALTER TABLE credit_record ADD INDEX idx_credit_record_student_no (student_no);

-- ---------- D3: 字符集统一 utf8mb4 / utf8mb4_unicode_ci ----------
-- 一、用户与组织
ALTER TABLE sys_department CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE sys_role CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE sys_user CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 二、活动管理
ALTER TABLE activity CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE activity_plan CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE activity_agenda CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE seat_map CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE score_rule CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE score_record CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE signin CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE signin_roster CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE signin_field_config CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE task CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 三、例行事务（排班/考勤/无课表/课程表）
ALTER TABLE schedule CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE schedule_person CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE attendance CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- 注：free_schedule 已于 V11 删除，故此处不含
ALTER TABLE no_class_schedule CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE course_schedule CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE schedule_config CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 四、党务台账
ALTER TABLE party_member CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE party_stage CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE party_roster CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE party_investigation CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE party_register CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE party_transfer CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 五、内容与宣传
ALTER TABLE article CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE news CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 六、档案与资产
ALTER TABLE file_record CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE material CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE template_asset CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE credit_record CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE announcement CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 七、成员花名册（V13）
ALTER TABLE member_session CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE member CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
