-- ===================== 排班/考勤 userId 绑定（PRD-v5 F5） =====================
-- 现状：schedule_person.user_id 基本为空，靠 person_name 字符串关联，同名混淆、无法按人聚合。
-- 本迁移按「姓名唯一命中」回填 user_id：仅当该姓名在在职用户（deleted=0）中恰好一人时回填，
-- 多人同名或无匹配保持 NULL（后续由前端选人组件在编辑时补全——不自动猜，宁缺勿错）。

-- 0) collation 对齐（同 V18/V17 做法）：跨表比较 person_name = real_name 前统一字符序，
--    避免 Illegal mix of collations（utf8mb4_0900_ai_ci vs utf8mb4_unicode_ci）。
ALTER TABLE schedule_person CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE sys_user CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 1) 姓名唯一命中回填（关联子查询写法，MySQL/H2 通用）
UPDATE schedule_person SET user_id = (
    SELECT su.id FROM sys_user su
    WHERE su.real_name = schedule_person.person_name AND su.deleted = 0
    LIMIT 1
)
WHERE user_id IS NULL
  AND person_name IS NOT NULL AND person_name <> ''
  -- 姓名唯一命中才回填（同名多人在真实场景存在）
  AND (SELECT COUNT(*) FROM sys_user x
       WHERE x.real_name = schedule_person.person_name AND x.deleted = 0) = 1;
