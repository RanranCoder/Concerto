-- ===================== 唯一键复合化（A3） =====================
-- 背景：多租户下，原本「全局唯一」的键会让不同组织无法使用同名数据
--   （例如两个组织都有「组织部」；两个组织的「第九届」等等）。
-- 本迁移把这类键改为「组织内唯一」。
--
-- 说明：
--   · sys_user.username 保持**全局唯一**（登录按用户名全局查找，改复合会引入歧义）。
--   · sys_role.code 为「内置(org_id NULL) + 组织自定义」双层，暂不改（V7 种子为全局）。
--   · 命名约束：新键统一加 _org 后缀，便于辨认。
--
-- ⚠️ 与 db/migration-h2/V34 不一致：H2 版为说明性空操作（见该文件）。

-- 1) sys_department.name：全局唯一 → (org_id, parent_id, name)
ALTER TABLE sys_department DROP INDEX name;
ALTER TABLE sys_department ADD UNIQUE KEY uk_dept_org_parent_name (org_id, parent_id, name);

-- 2) member_session.name：全局 → 组织内
ALTER TABLE member_session DROP INDEX uk_member_session_name;
ALTER TABLE member_session ADD UNIQUE KEY uk_member_session_name_org (org_id, name, deleted);

-- 3) kb_chunk：分块来源键 → 组织内
ALTER TABLE kb_chunk DROP INDEX uk_kb_chunk_source;
ALTER TABLE kb_chunk ADD UNIQUE KEY uk_kb_chunk_source_org (org_id, source_key, seq);

-- 4) kb_snapshot：快照幂等键 → 组织内
ALTER TABLE kb_snapshot DROP INDEX uk_kb_snapshot;
ALTER TABLE kb_snapshot ADD UNIQUE KEY uk_kb_snapshot_org (org_id, spec_code, scope_type, scope_id, as_of);
