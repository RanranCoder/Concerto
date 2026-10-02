-- ===================== V25 的 H2 分支（测试 profile） =====================
-- 背景：MySQL 版 V25 用 `SET @c = (SELECT ...)` + `PREPARE` 动态 DDL，
-- H2 不支持该语法（H2 2.4 报 "Syntax error ... SET @ddl = IF(...)"）。
-- 而在全新 H2 库上，V8 的 `CREATE TABLE seat_layout` 已包含 `color_labels` 列
-- （见 db/migration/V8__seat_layout.sql），故本迁移在 H2 上等价于空操作。
-- 为防极端情况（H2 库结构异常）仍用 H2 原生 IF NOT EXISTS 兜底，幂等安全。
--
-- 说明：本文件与 db/migration-mysql/V25__fix_seat_layout_color_labels.sql 同版本，
-- 由 spring.flyway.locations 按 profile 二选一（见 application.yml），不会重复执行。

ALTER TABLE seat_layout ADD COLUMN IF NOT EXISTS color_labels TEXT;
