-- ===================== /uploads 静态直链收紧配套迁移 =====================
-- 背景（2026-09 安全收尾 S1）：
--   1) /uploads/** 由匿名可读收紧为需登录（SecurityConfig），历史直链改由 pams_token Cookie 兜底鉴权；
--   2) 群二维码统一改存 /api/files/{id}/download 下载地址，与文章长图/策划书插图口径一致。

-- 0) collation 对齐：group_chat 在部分环境为 utf8mb4_0900_ai_ci（MySQL 8 默认），与 file_record
--    （utf8mb4_unicode_ci）跨表字符串比较会报 Illegal mix of collations。沿用 V17 的做法统一到
--    unicode_ci（docker-compose 已设 --collation-server=utf8mb4_unicode_ci，生产环境此语句为幂等空转）。
ALTER TABLE group_chat CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE file_record CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 1) 群二维码历史直链 /uploads/{path} → /api/files/{id}/download（按 file_record.path 关联，幂等：
--    LIKE '/uploads/%' + EXISTS 条件保证已转换/无对应文件的行不再重跑。UPDATE...JOIN 语法 H2 不支持，
--    用关联子查询，MySQL/H2 通用。）
UPDATE group_chat SET qr_code_url = CONCAT('/api/files/',
    (SELECT fr.id FROM file_record fr
      WHERE CONCAT('/uploads/', fr.path) = group_chat.qr_code_url
      LIMIT 1),
    '/download')
WHERE qr_code_url LIKE '/uploads/%'
  AND EXISTS (SELECT 1 FROM file_record fr
              WHERE CONCAT('/uploads/', fr.path) = group_chat.qr_code_url);
