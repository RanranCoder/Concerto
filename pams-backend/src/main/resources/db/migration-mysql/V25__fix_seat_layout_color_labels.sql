-- ===================== 修复 seat_layout.color_labels 缺列（schema 漂移） =====================
-- 背景：pams_db 先于 Flyway 由早期建表脚本创建（application.yml「已由 Task 2 建表」注释可证），
-- V8 运行时 CREATE TABLE IF NOT EXISTS 对已存在的旧结构 seat_layout 是空操作，
-- 导致实体 SeatLayout.color_labels（@Column "color_labels"）对应的列从未建出。
-- 后果：seat_layout 的任何实体级查询（编辑器详情、模板列表、模板复制）全部抛
--       Unknown column 'sl1_0.color_labels'；列表类接口因走投影/VO 才幸免。
-- 本迁移用 information_schema 判存后加列——幂等：
--   · 本库（缺列）→ 真实执行 ALTER；
--   · 全新库（V8 的 CREATE 已含该列）→ 跳过，不报 Duplicate column。

SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME  = 'seat_layout'
    AND COLUMN_NAME = 'color_labels'
);

SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE seat_layout ADD COLUMN color_labels TEXT NULL COMMENT ''自定义配色 JSON：[{color,label}]'' AFTER seat_data',
  'SELECT ''color_labels 已存在，跳过加列'' AS note'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
