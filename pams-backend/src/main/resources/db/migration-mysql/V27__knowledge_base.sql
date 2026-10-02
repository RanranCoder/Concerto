-- ===================== 知识库问答（灵思「对话」档位）基础表（V27） =====================
-- 对应方案：docs/PAMS-灵思知识库问答方案-v1.md（D12 全域知识源）
--
-- 设计要点（读完再改）：
--   1) kb_chunk 不隶属于 kb_document，而隶属于**任意业务源**：
--      用 (source_type, source_id) + source_key 定位，公告/稿件/策划书/人工文档共用一张表。
--      D12 的架构形状要求就是"新增一个源 = 一个适配器类 + 一条准入配置"，表结构必须支持这一点。
--   2) kb_chunk 的权限字段（owner_dept_id / visibility / required_permission）是
--      **索引期的保守缓存**，只用于 SQL 粗筛保住性能。正确性由 PermissionResolver
--      在检索期回源复核提供（方案 §6.4）。因此这些字段过期只会"少给"、不会"多给"。
--   3) as_of 是"数据截止时间"：① 来自 kb_snapshot 的分块必填 ② 文档类分块为 NULL。
--      方案 R16 要求 snapshot 类引用强制携带 asOf，所以这个列必须在 chunk 上，
--      否则引用回填时还得回查来源表。
--   4) ⚠️ FULLTEXT 索引**刻意不在这里建**，见文末说明。
--
-- 幂等策略：CREATE TABLE IF NOT EXISTS（全新库/老库都能跑）；
--           ALTER 走 V25/V26 的 information_schema 判存 + PREPARE 动态执行。

-- ---------------------------------------------------------------------------
-- 1. kb_document —— 人工录入的知识源（D12 里的 ManualDocSource）
--    项目里原本**不存在**"制度/规范性文件"这一类载体：最接近的 announcement 是
--    "通知"语义（时间性、一次性），不是"制度"（常驻、有生效期、会被修订）。
--    而制度型问题恰恰是知识库最高频的场景，所以这张表必须新建。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_document (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(200) NOT NULL COMMENT '文档标题',
  doc_type VARCHAR(30) NOT NULL DEFAULT 'REGULATION'
    COMMENT 'REGULATION 制度 / POLICY 上级文件 / MANUAL 操作指引 / FAQ 常见问答',
  category VARCHAR(50) NULL COMMENT '业务分类（如 党员发展 / 党费 / 组织生活），用于范围过滤与统计',
  body MEDIUMTEXT NOT NULL COMMENT '正文（录入时已过 HtmlSanitizer）',
  summary VARCHAR(500) NULL COMMENT '摘要，用于列表与引用展示',
  source_type VARCHAR(30) NULL COMMENT '派生来源类型：announcement/article/... ；人工录入为 NULL',
  source_id BIGINT NULL COMMENT '派生来源主键',
  version VARCHAR(30) NULL COMMENT '版本标识，如「2024 修订」',
  effective_from DATE NULL COMMENT '生效日',
  effective_to DATE NULL COMMENT '废止日；NULL = 长期有效。问「现行规定」时按此过滤',
  owner_dept_id BIGINT NULL COMMENT '归属部门（行级过滤用）',
  visibility VARCHAR(20) NOT NULL DEFAULT 'ALL' COMMENT 'ALL 全员 / LEADER 部长及以上',
  required_permission VARCHAR(60) NULL COMMENT '附加权限码要求；与 visibility 取「与」',
  sensitivity VARCHAR(20) NOT NULL DEFAULT 'normal' COMMENT 'normal/sensitive；restricted 不落库（索引期排除）',
  status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED' COMMENT 'DRAFT/PUBLISHED/ARCHIVED',
  deleted TINYINT NOT NULL DEFAULT 0,
  created_by BIGINT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_kb_doc_type (doc_type, status, deleted),
  INDEX idx_kb_doc_visibility (visibility, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库-人工录入文档';

-- ---------------------------------------------------------------------------
-- 2. kb_chunk —— 检索单元（所有知识源共用）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_chunk (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  source_type VARCHAR(30) NOT NULL COMMENT 'announcement/article/activity_plan/manual/snapshot',
  source_id BIGINT NOT NULL COMMENT '源主键（manual 时指向 kb_document.id）',
  source_key VARCHAR(80) NOT NULL COMMENT 'source_type:source_id，唯一键，用于去重与级联清理',
  seq INT NOT NULL COMMENT '源内序号',
  title VARCHAR(200) NULL COMMENT '所属标题（分块继承的标题），标题匹配权重更高',
  path VARCHAR(300) NULL COMMENT '标题路径，如「第三章 第二十六条」—— 引用的可读性靠它',
  content TEXT NOT NULL COMMENT '分块正文',
  start_offset INT NULL COMMENT '原文偏移，P2 精确高亮用（一期写 NULL，避免二次迁移）',
  end_offset INT NULL,
  token_len INT NULL COMMENT '估算字符数，用于上下文预算',
  tier VARCHAR(10) NOT NULL DEFAULT 'T1' COMMENT 'D12 分级：T1 全文级（参与 BM25）/ T2 字段级（仅标题匹配）',
  as_of DATETIME NULL COMMENT '数据截止时间；snapshot 类必填，文档类为 NULL（方案 §2.2 / R16）',
  owner_dept_id BIGINT NULL COMMENT '索引期粗筛用（缓存，非真相）',
  visibility VARCHAR(20) NOT NULL DEFAULT 'ALL' COMMENT 'ALL / LEADER（保守：宁严不宽）',
  required_permission VARCHAR(60) NULL,
  sensitivity VARCHAR(20) NOT NULL DEFAULT 'normal',
  deleted TINYINT NOT NULL DEFAULT 0,
  indexed_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '本次索引时间（源变更后重建会刷新）',
  UNIQUE KEY uk_kb_chunk_source (source_key, seq),
  INDEX idx_kb_chunk_perm (visibility, owner_dept_id, deleted),
  INDEX idx_kb_chunk_tier (tier, deleted),
  INDEX idx_kb_chunk_source (source_type, source_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库-分块';

-- ---------------------------------------------------------------------------
-- 3. kb_retrieval_log —— 检索记录（知识库页「检索记录」Tab 的数据来源）
--    价值有两层：
--      a) 审计：谁在什么时候问了什么、命中了哪些源、有没有被权限复核拦下
--      b) 运营：拒答原因是"索引坏了"还是"语料不足"，看趋势即可区分；
--         未命中高频问题 → 指导补录语料 / 补别名表（方案 §11 指标）
--    ⚠️ query 是用户原话，可能含个人信息 → 落库前过 PolicyGuard.sanitizeArgs 同款遮罩。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_retrieval_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  conversation_id BIGINT NULL,
  message_id BIGINT NULL COMMENT '关联的 assistant 消息（便于从问答跳回检索明细）',
  stream_id VARCHAR(64) NULL,
  query_text TEXT NOT NULL COMMENT '检索用 query（已脱敏；可能是查询改写后的结果）',
  raw_question TEXT NULL COMMENT '用户原话（已脱敏），用于比对"改写有没有改错"',
  mode VARCHAR(20) NOT NULL DEFAULT 'kb',
  hit_count INT NOT NULL DEFAULT 0 COMMENT '粗筛命中数',
  allowed_count INT NOT NULL DEFAULT 0 COMMENT '回源复核通过数（真正进 prompt 的）',
  filtered_count INT NOT NULL DEFAULT 0 COMMENT '被权限复核拦下数 —— 非 0 说明粗筛偏松，是索引期字段的体检指标',
  top_score DECIMAL(10,4) NULL COMMENT '最高相关度，用于阈值调参',
  scan_ms INT NULL COMMENT '检索耗时（判断是否落回 LIKE 降级）',
  total_ms INT NULL COMMENT '本轮总耗时',
  skip_reason VARCHAR(40) NULL COMMENT 'NO_HIT 零命中 / BELOW_THRESHOLD 低于阈值 / NO_CITATION 零引用 / NULL 正常作答',
  provider VARCHAR(20) NULL,
  degraded TINYINT NOT NULL DEFAULT 0 COMMENT '是否规则模式兜底',
  hits_json JSON NULL COMMENT '命中明细：[{sourceType,sourceId,sourceKey,seq,path,score,allowed}]',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_kb_log_user (user_id, created_at),
  INDEX idx_kb_log_time (created_at),
  INDEX idx_kb_log_skip (skip_reason, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库-检索记录';

-- ---------------------------------------------------------------------------
-- 4. agent_message 补列：mode + citations_json
--    必须走动态 ALTER —— pams_db 先于 Flyway 存在，老库不会因为
--    CREATE TABLE 里加了列就自动获得新列（本项目已踩过这个坑）。
-- ---------------------------------------------------------------------------
SET @msg_mode_col = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME  = 'agent_message'
    AND COLUMN_NAME = 'mode'
);

SET @ddl = IF(@msg_mode_col = 0,
  'ALTER TABLE agent_message ADD COLUMN mode VARCHAR(20) NULL COMMENT ''本轮运行模式 readonly/standard/kb；老数据为 NULL，读取按 standard 兜底'' AFTER refs_json',
  'SELECT ''agent_message.mode 已存在，跳过'' AS note'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @msg_cit_col = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME  = 'agent_message'
    AND COLUMN_NAME = 'citations_json'
);

SET @ddl = IF(@msg_cit_col = 0,
  'ALTER TABLE agent_message ADD COLUMN citations_json JSON NULL COMMENT ''本轮答案的依据（服务端回填的出处数组）'' AFTER mode',
  'SELECT ''agent_message.citations_json 已存在，跳过'' AS note'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 5. 权限码种子：kb:read / kb:manage
--    kb:read   —— 查看知识库页面（内容台账 / 检索记录）
--    kb:manage —— 人工文档录入、重建索引
--    授予范围：kb:manage 给领导角色（会长文档，因此不做成全员可写）；
--              kb:read 给全部角色（与 `/agent` 对所有登录用户开放一致 ——
--              知识问答本身是"人人都能用"的能力，只是能看到的内容受可见性约束）。
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO permission (code, name, module, sort_order) VALUES
('kb:read',   '查看知识库', 'kb', 1),
('kb:manage', '管理知识库', 'kb', 2);

-- 全角色可读
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

-- 领导角色可管理
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

-- ===========================================================================
-- 6. 关于中文全文索引（刻意不在此处创建）
--
-- 中文需要 `FULLTEXT ... WITH PARSER ngram`，默认分词器对中文基本无效。
-- 但**把它写进迁移是不安全的**：ngram 解析器在个别环境不可用（版本/构建差异），
-- 而 Flyway 迁移失败会让**整个应用启动不了** —— 用"问答检索可能变慢"
-- 换"服务起不来"是不划算的。
--
-- 因此索引改由应用层初始化器（KnowledgeIndexInitializer）在启动时创建：
--   · 探测 information_schema.STATISTICS 是否已有 ft_kb_chunk_content
--   · 不存在则尝试 CREATE FULLTEXT INDEX ... WITH PARSER ngram
--   · 失败则降级为 LIKE 检索并打 WARN 告警（功能可用，性能下降）
-- 对应方案 §4.2 的"启动期探测 + 自动回退 LIKE + 告警"，也让 §14 的
-- 「检索耗时 P95 < 300ms」成为可观测的降级信号。
-- ===========================================================================
