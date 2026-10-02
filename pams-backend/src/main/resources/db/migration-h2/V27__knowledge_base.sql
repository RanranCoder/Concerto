-- ===================== V27 的 H2 分支（测试 profile） =====================
-- MySQL 版 V27 的建表部分是双库兼容的（H2 已通过），仅第 4 段的动态 ALTER 不兼容。
-- 本文件 = 建表（照抄 MySQL 版）+ 用 H2 原生 IF NOT EXISTS 替代动态 ALTER + 权限种子。
-- 全文索引段（只读注释）在 H2 下无 DDL，省略。

CREATE TABLE IF NOT EXISTS kb_document (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(200) NOT NULL,
  doc_type VARCHAR(30) NOT NULL DEFAULT 'REGULATION',
  category VARCHAR(50) NULL,
  body MEDIUMTEXT NOT NULL,
  summary VARCHAR(500) NULL,
  source_type VARCHAR(30) NULL,
  source_id BIGINT NULL,
  version VARCHAR(30) NULL,
  effective_from DATE NULL,
  effective_to DATE NULL,
  owner_dept_id BIGINT NULL,
  visibility VARCHAR(20) NOT NULL DEFAULT 'ALL',
  required_permission VARCHAR(60) NULL,
  sensitivity VARCHAR(20) NOT NULL DEFAULT 'normal',
  status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',
  deleted TINYINT NOT NULL DEFAULT 0,
  created_by BIGINT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_kb_doc_type (doc_type, status, deleted),
  INDEX idx_kb_doc_visibility (visibility, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库-人工录入文档';

CREATE TABLE IF NOT EXISTS kb_chunk (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  source_type VARCHAR(30) NOT NULL,
  source_id BIGINT NOT NULL,
  source_key VARCHAR(80) NOT NULL,
  seq INT NOT NULL,
  title VARCHAR(200) NULL,
  path VARCHAR(300) NULL,
  content TEXT NOT NULL,
  start_offset INT NULL,
  end_offset INT NULL,
  token_len INT NULL,
  tier VARCHAR(10) NOT NULL DEFAULT 'T1',
  as_of DATETIME NULL,
  owner_dept_id BIGINT NULL,
  visibility VARCHAR(20) NOT NULL DEFAULT 'ALL',
  required_permission VARCHAR(60) NULL,
  sensitivity VARCHAR(20) NOT NULL DEFAULT 'normal',
  deleted TINYINT NOT NULL DEFAULT 0,
  indexed_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_kb_chunk_source (source_key, seq),
  INDEX idx_kb_chunk_perm (visibility, owner_dept_id, deleted),
  INDEX idx_kb_chunk_tier (tier, deleted),
  INDEX idx_kb_chunk_source (source_type, source_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库-分块';

CREATE TABLE IF NOT EXISTS kb_retrieval_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  conversation_id BIGINT NULL,
  message_id BIGINT NULL,
  stream_id VARCHAR(64) NULL,
  query_text TEXT NOT NULL,
  raw_question TEXT NULL,
  mode VARCHAR(20) NOT NULL DEFAULT 'kb',
  hit_count INT NOT NULL DEFAULT 0,
  allowed_count INT NOT NULL DEFAULT 0,
  filtered_count INT NOT NULL DEFAULT 0,
  top_score DECIMAL(10,4) NULL,
  scan_ms INT NULL,
  total_ms INT NULL,
  skip_reason VARCHAR(40) NULL,
  provider VARCHAR(20) NULL,
  degraded TINYINT NOT NULL DEFAULT 0,
  hits_json JSON NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_kb_log_user (user_id, created_at),
  INDEX idx_kb_log_time (created_at),
  INDEX idx_kb_log_skip (skip_reason, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库-检索记录';

-- agent_message 补列（替代 MySQL 版的动态 ALTER）
ALTER TABLE agent_message ADD COLUMN IF NOT EXISTS mode VARCHAR(20) NULL;
ALTER TABLE agent_message ADD COLUMN IF NOT EXISTS citations_json JSON NULL;

-- 权限码种子（与 MySQL 版一致）
INSERT IGNORE INTO permission (code, name, module, sort_order) VALUES
('kb:read',   '查看知识库', 'kb', 1),
('kb:manage', '管理知识库', 'kb', 2);

INSERT IGNORE INTO role_permission (role, permission_id)
SELECT r.role, p.id FROM (
  SELECT 'TEACHER' AS role UNION ALL
  SELECT 'DIRECTOR' UNION ALL
  SELECT 'ORG_LEADER' UNION ALL
  SELECT 'SECRETARY_LEADER' UNION ALL
  SELECT 'MEDIA_LEADER' UNION ALL
  SELECT 'TECH_LEADER' UNION ALL
  SELECT 'STAFF'
) r
JOIN permission p ON p.code = 'kb:read';

INSERT IGNORE INTO role_permission (role, permission_id)
SELECT r.role, p.id FROM (
  SELECT 'TEACHER' AS role UNION ALL
  SELECT 'DIRECTOR' UNION ALL
  SELECT 'ORG_LEADER' UNION ALL
  SELECT 'SECRETARY_LEADER' UNION ALL
  SELECT 'MEDIA_LEADER' UNION ALL
  SELECT 'TECH_LEADER'
) r
JOIN permission p ON p.code = 'kb:manage';
