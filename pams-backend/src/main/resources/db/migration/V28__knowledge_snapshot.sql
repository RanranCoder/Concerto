-- ===================== 知识库事实快照（V28，D12 的 B 形态） =====================
-- 对应方案：docs/PAMS-灵思知识库问答方案-v1.md §2.2（数据的三种形态）
--
-- 背景（为什么需要这张表）：
--   D12 要求"平台系统中一切产生知识的载体都是知识源"，但数据必须区分形态：
--     A 原始明细   → 不进索引（签到逐条、学号电话、党员个人材料 —— 含不可脱敏的个人信息）
--     B 聚合快照   → **进索引**（"截至 X 日，本届成员 12 人，其中组织部 5 人"）
--     C 历史叙述   → 进索引（已冻结的事实）
--   此前知识库只能答文档类问题（制度/公告/稿件/策划书/成员条目），
--   问"我们有多少人""最近活动办了几场"一律答不上 —— 因为这类数据在形态 A 里，
--   **而形态 A 是刻意不进索引的**。
--   本表就是形态 B 的落点：把聚合事实写成人读得懂的叙述，带上数据截止时间再进索引。
--
-- 三条设计约束（改动前请先读）：
--   1) **只存聚合，绝不存明细**。content 里不得出现人名、学号、手机号、政治面貌等
--      个人可识别信息。这条由 SnapshotSpecRegistry 的构造方式（只接受 NameCount/计数）
--      与 SnapshotNarrationTest 硬断言共同保证 —— 不能只靠"写的时候注意点"。
--   2) **as_of 强制非空**。它是"这是某时刻的数字"的唯一标识；缺了它，用户会把
--      快照当"当前值"读，拿着过期数字做决策（方案 R16，且引用回填会拒绝出网）。
--   3) 快照可重复生成（幂等），保留历史版本用于对比"为什么数字变了"。

CREATE TABLE IF NOT EXISTS kb_snapshot (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  spec_code VARCHAR(60) NOT NULL COMMENT '快照模板标识，对应 SnapshotSpecRegistry 里预注册的 spec',
  title VARCHAR(200) NOT NULL COMMENT '人读的标题，如「第 40 届成员构成」',
  content MEDIUMTEXT NOT NULL COMMENT '叙述化聚合事实（**只允许计数与组织名，不得含个人信息**）',
  as_of DATETIME NOT NULL COMMENT '数据截止时间，**强制非空**（方案 R16）',
  scope_type VARCHAR(30) NOT NULL COMMENT '作用域类型：GLOBAL / MEMBER_SESSION / ACTIVITY',
  -- ⚠️ 下面这条 COMMENT 必须写成单行：SQL 不支持跨行字符串拼接（那是 Java 的写法）。
  --    曾经拆成两行，第二行被当成新的字符串字面量，直接语法错误 1064，
  --    且 Flyway 迁移失败会让整个应用起不来（教训已记入 MEMORY）。
  scope_id BIGINT NOT NULL DEFAULT 0 COMMENT '作用域主键；0 = 全局。刻意不用 NULL：MySQL 唯一索引把 NULL 视为互不相同，用 NULL 会让 (spec_code, scope_type, scope_id, as_of) 的幂等约束失效',
  owner_dept_id BIGINT NULL COMMENT '归属部门（行级过滤预留）',
  visibility VARCHAR(20) NOT NULL DEFAULT 'ALL'
    COMMENT 'ALL 全员 / LEADER 部长及以上。**必须由 spec 显式声明**，与来源页面的守卫一致',
  required_permission VARCHAR(60) NULL COMMENT '附加权限码要求；与 visibility 取「与」',
  sensitivity VARCHAR(20) NOT NULL DEFAULT 'normal',
  generated_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '生成时刻（用于排查"为什么数字变了"）',
  deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_kb_snapshot (spec_code, scope_type, scope_id, as_of),
  INDEX idx_kb_snapshot_latest (spec_code, scope_type, scope_id, deleted, as_of)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库-事实快照（聚合事实，不含明细）';

-- 说明：kb_chunk.as_of 已存在（V27），快照分块会带上对应的数据截止时间。
-- 引用回填时，sourceType='snapshot' 且 asOf 为空的引用会被 CitationMapper 直接拒绝出网
-- —— 这是 R16 的硬断言，不需要在本迁移里再做约束。
