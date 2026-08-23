-- 软删唯一键缺陷修复（C2 + C3 的 DDL 部分）
-- 问题：deleted 列作复合唯一键成分时，固定值 1 导致同键第二次软删必撞唯一键；
--       sys_user.username 硬唯一与软删冲突。
-- 方案：软删时 deleted 置为该行主键 id（0=活跃 / id=已删）。已删行彼此 deleted 不同，
--       天然不撞 (key, deleted) 复合唯一键；活跃行 deleted=0 仍受唯一键保护防重复。
--       @SQLRestriction("deleted = 0") 与查询逻辑不变。
-- 注意：sys_user.username 的单列唯一键保留（H2/MySQL 内联 UNIQUE 索引名不一致，无法跨库 DROP），
--       C3「删除账号后重建同名」由应用层复活逻辑修复（UserService.createUser / MemberAccountImportService）。

-- 1) 列类型 TINYINT→BIGINT（容纳主键 id）
ALTER TABLE member MODIFY COLUMN deleted BIGINT NOT NULL DEFAULT 0;
ALTER TABLE member_session MODIFY COLUMN deleted BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sys_user MODIFY COLUMN deleted BIGINT NOT NULL DEFAULT 0;

-- 2) 数据回填：存量 deleted<>0 的行置为各自主键 id，消除既存冲突
UPDATE member SET deleted = id WHERE deleted <> 0;
UPDATE member_session SET deleted = id WHERE deleted <> 0;
UPDATE sys_user SET deleted = id WHERE deleted <> 0;

