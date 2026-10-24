"""Generate the PAMS demo organization seed SQL and credential sheets.

The roster mirrors the department/position table supplied by the user:
- 主任室 (organization-level, no department): 1 主任 + 2 副主任
- 青年科技部: 1 部长 + 1 副部长 + 10 干事
- 文秘部: 1 部长 + 2 副部长 + 14 干事
- 新媒体中心: 1 部长 + 3 副部长 + 12 干事
- 组织部: 1 部长 + 3 副部长 + 16 干事
Plus a 指导老师 account and a system owner account (not counted in the chart).
"""

from __future__ import annotations

import csv
import json
from pathlib import Path

OUT_DIR = Path(__file__).resolve().parent
PASSWORD = "123456"
# BCrypt(10) hash of 123456, verified against Spring Security's BCryptPasswordEncoder.
PASSWORD_HASH = "$2a$10$HVoJNSAoRvgntUtU2VkDUeQnj7XjvHpViuGNbPllT9cryVMxYGYFm"

ORG_NAME = "信息与智能工程学院党建办公室"
ORG_SHORT = "信智党建办"
ORG_TYPE = "PARTY"
ORG_THEME = "#A61B29"
ORG_PACKS = ["party", "volunteer", "club"]

DEPARTMENTS = [
    # key, name, unit_type, sort, leader username
    ("director", "主任办公室", "中心", 1, "zhuren"),
    ("tech", "青年科技部", "部门", 2, "techleader"),
    ("sec", "文秘部", "部门", 3, "secleader"),
    ("media", "新媒体中心", "中心", 4, "medialeader"),
    ("org", "组织部", "部门", 5, "orgleader"),
]

ROLE_NAMES = {
    "TEACHER": "指导老师",
    "DIRECTOR": "主任",
    "ORG_LEADER": "组织部长",
    "SECRETARY_LEADER": "文秘部长",
    "MEDIA_LEADER": "新媒体部长",
    "TECH_LEADER": "青年科技部长",
    "STAFF": "干事",
}

# username, real name, department key or None, role code, position label
ACCOUNTS = [
    ("admin", "系统管理员", "director", "TEACHER", "系统管理员"),
    ("teacher", "指导老师", "director", "TEACHER", "指导老师"),
    ("zhuren", "陈志远", "director", "DIRECTOR", "主任"),
    ("fuzhuren1", "李慧敏", "director", "DIRECTOR", "副主任"),
    ("fuzhuren2", "周建国", "director", "DIRECTOR", "副主任"),
    # 青年科技部
    ("techleader", "王浩然", "tech", "TECH_LEADER", "部长"),
    ("techdeputy1", "刘思远", "tech", "TECH_LEADER", "副部长"),
    ("techstaff01", "张宇轩", "tech", "STAFF", "干事"),
    ("techstaff02", "李泽楷", "tech", "STAFF", "干事"),
    ("techstaff03", "王雨欣", "tech", "STAFF", "干事"),
    ("techstaff04", "陈嘉豪", "tech", "STAFF", "干事"),
    ("techstaff05", "杨若彤", "tech", "STAFF", "干事"),
    ("techstaff06", "赵子涵", "tech", "STAFF", "干事"),
    ("techstaff07", "黄俊杰", "tech", "STAFF", "干事"),
    ("techstaff08", "吴梦琪", "tech", "STAFF", "干事"),
    ("techstaff09", "徐博文", "tech", "STAFF", "干事"),
    ("techstaff10", "孙悦", "tech", "STAFF", "干事"),
    # 文秘部
    ("secleader", "林晓彤", "sec", "SECRETARY_LEADER", "部长"),
    ("secdeputy1", "郑雅静", "sec", "SECRETARY_LEADER", "副部长"),
    ("secdeputy2", "何嘉怡", "sec", "SECRETARY_LEADER", "副部长"),
    ("secstaff01", "周雨桐", "sec", "STAFF", "干事"),
    ("secstaff02", "陈思琪", "sec", "STAFF", "干事"),
    ("secstaff03", "林佳怡", "sec", "STAFF", "干事"),
    ("secstaff04", "黄诗涵", "sec", "STAFF", "干事"),
    ("secstaff05", "吴静怡", "sec", "STAFF", "干事"),
    ("secstaff06", "郑欣妍", "sec", "STAFF", "干事"),
    ("secstaff07", "王梦洁", "sec", "STAFF", "干事"),
    ("secstaff08", "李佳琪", "sec", "STAFF", "干事"),
    ("secstaff09", "张欣然", "sec", "STAFF", "干事"),
    ("secstaff10", "刘诗雨", "sec", "STAFF", "干事"),
    ("secstaff11", "陈雅雯", "sec", "STAFF", "干事"),
    ("secstaff12", "杨子晴", "sec", "STAFF", "干事"),
    ("secstaff13", "赵佳宁", "sec", "STAFF", "干事"),
    ("secstaff14", "徐若曦", "sec", "STAFF", "干事"),
    # 新媒体中心
    ("medialeader", "苏子墨", "media", "MEDIA_LEADER", "部长"),
    ("mediadeputy1", "唐嫣然", "media", "MEDIA_LEADER", "副部长"),
    ("mediadeputy2", "冯亦凡", "media", "MEDIA_LEADER", "副部长"),
    ("mediadeputy3", "董欣怡", "media", "MEDIA_LEADER", "副部长"),
    ("mediastaff01", "陈一鸣", "media", "STAFF", "干事"),
    ("mediastaff02", "李梓涵", "media", "STAFF", "干事"),
    ("mediastaff03", "王俊熙", "media", "STAFF", "干事"),
    ("mediastaff04", "张晨曦", "media", "STAFF", "干事"),
    ("mediastaff05", "刘思琪", "media", "STAFF", "干事"),
    ("mediastaff06", "黄子轩", "media", "STAFF", "干事"),
    ("mediastaff07", "吴嘉怡", "media", "STAFF", "干事"),
    ("mediastaff08", "周浩宇", "media", "STAFF", "干事"),
    ("mediastaff09", "徐梦瑶", "media", "STAFF", "干事"),
    ("mediastaff10", "孙嘉豪", "media", "STAFF", "干事"),
    ("mediastaff11", "马雨欣", "media", "STAFF", "干事"),
    ("mediastaff12", "朱子涵", "media", "STAFF", "干事"),
    # 组织部
    ("orgleader", "马嘉诚", "org", "ORG_LEADER", "部长"),
    ("orgdeputy1", "罗诗涵", "org", "ORG_LEADER", "副部长"),
    ("orgdeputy2", "高子豪", "org", "ORG_LEADER", "副部长"),
    ("orgdeputy3", "谢婉婷", "org", "ORG_LEADER", "副部长"),
    ("orgstaff01", "韩雨桐", "org", "STAFF", "干事"),
    ("orgstaff02", "曹俊杰", "org", "STAFF", "干事"),
    ("orgstaff03", "邓紫琪", "org", "STAFF", "干事"),
    ("orgstaff04", "许志强", "org", "STAFF", "干事"),
    ("orgstaff05", "傅晓彤", "org", "STAFF", "干事"),
    ("orgstaff06", "沈佳豪", "org", "STAFF", "干事"),
    ("orgstaff07", "曾雅琳", "org", "STAFF", "干事"),
    ("orgstaff08", "彭浩然", "org", "STAFF", "干事"),
    ("orgstaff09", "吕思妍", "org", "STAFF", "干事"),
    ("orgstaff10", "苏俊宇", "org", "STAFF", "干事"),
    ("orgstaff11", "蒋若曦", "org", "STAFF", "干事"),
    ("orgstaff12", "蔡明轩", "org", "STAFF", "干事"),
    ("orgstaff13", "潘雨欣", "org", "STAFF", "干事"),
    ("orgstaff14", "杜嘉诚", "org", "STAFF", "干事"),
    ("orgstaff15", "戴诗涵", "org", "STAFF", "干事"),
    ("orgstaff16", "姚子健", "org", "STAFF", "干事"),
]

POSITIONS = [
    # name, level, role code, sort
    ("主任", 4, "DIRECTOR", 1),
    ("副主任", 4, "DIRECTOR", 2),
    ("青年科技部部长", 3, "TECH_LEADER", 3),
    ("青年科技部副部长", 3, "TECH_LEADER", 4),
    ("文秘部部长", 3, "SECRETARY_LEADER", 5),
    ("文秘部副部长", 3, "SECRETARY_LEADER", 6),
    ("新媒体中心部长", 3, "MEDIA_LEADER", 7),
    ("新媒体中心副部长", 3, "MEDIA_LEADER", 8),
    ("组织部部长", 3, "ORG_LEADER", 9),
    ("组织部副部长", 3, "ORG_LEADER", 10),
    ("干事", 1, "STAFF", 11),
    ("指导老师", 5, "TEACHER", 12),
]

# 花名册职位码（与 MemberEnums.POSITION_LABELS 一致）
MEMBER_POSITION = {
    "主任": "DIRECTOR",
    "副主任": "SUB_DIRECTOR",
    "部长": "DEPT_HEAD",
    "副部长": "SUB_DEPT_HEAD",
    "干事": "STAFF",
}

# Tables whose rows are demo/business data and must be cleared.
CLEAR_TABLES = """
activity activity_agenda activity_plan activity_plan_review
agent_audit_log agent_benchmark agent_conversation agent_message
agent_run agent_step agent_task agent_tool_call
announcement announcement_read article attendance attendance_audit
course_schedule credit_record file_record
group_chat group_chat_category group_chat_department
kb_chunk kb_document kb_retrieval_log kb_snapshot
material member member_account_batch member_account_credential member_session
news no_class_schedule notification
org_audit_log org_authorization_change org_invitation org_position
org_role_delegation
party_investigation party_member party_register party_roster party_stage
party_transfer
schedule schedule_config schedule_person score_record score_rule
seat_layout seat_map sign_in_group signin signin_field_config signin_roster
sys_department sys_user task template_asset
""".split()


def sql_str(value: str | None) -> str:
    if value is None:
        return "NULL"
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def build_sql() -> str:
    lines: list[str] = []
    add = lines.append

    add("-- PAMS 演示数据：清空业务数据 + 重建组织架构与全角色账号")
    add("-- 生成脚本：scripts/demo-data/generate_demo_seed.py")
    add("-- 默认登录密码：123456（仅用于演示，请勿用于生产）")
    add("-- 说明：保留 Flyway、权限点、角色模板、组织 1 的授权配置与 Agent 定义；")
    add("--       清空其余全部业务数据、成员数据与账号数据。")
    add("")
    add("SET NAMES utf8mb4;")
    add("SET FOREIGN_KEY_CHECKS = 0;")
    add("")
    add("-- 1. 清空业务与账号数据")
    for table in CLEAR_TABLES:
        add(f"DELETE FROM `{table}`;")
    add("")
    add("-- 2. 移除其它组织及其授权数据，仅保留组织 1 的已发布授权")
    add("DELETE FROM role_grant WHERE org_id <> 1 OR role_id NOT IN (1,2,3,4,5,6,7);")
    add("DELETE FROM role_revision WHERE org_id <> 1 OR role_id NOT IN (1,2,3,4,5,6,7);")
    add("DELETE FROM org_domain_pack WHERE org_id <> 1;")
    add("DELETE FROM authorization_bootstrap WHERE org_id <> 1;")
    add("DELETE FROM sys_role WHERE id NOT IN (1,2,3,4,5,6,7);")
    add("DELETE FROM organization WHERE id <> 1;")
    add("")
    add("-- 3. 组织资料：信息与智能工程学院党建办公室")
    add(
        "UPDATE organization SET "
        f"name={sql_str(ORG_NAME)}, type={sql_str(ORG_TYPE)}, "
        f"short_name={sql_str(ORG_SHORT)}, "
        f"theme_json={sql_str(json.dumps({'primaryColor': ORG_THEME}, ensure_ascii=False))}, "
        f"enabled_packs={sql_str(json.dumps(ORG_PACKS))}, "
        "status=1, owner_user_id=1, authz_version=authz_version+1, "
        "updated_at=NOW() WHERE id=1;"
    )
    add(
        "UPDATE org_domain_pack SET enabled=1, activation_version=1 "
        "WHERE org_id=1 AND pack_code='party';"
    )
    add(
        "INSERT INTO org_domain_pack(org_id, pack_code, enabled, activation_version) "
        "SELECT 1, 'party', 1, 1 WHERE NOT EXISTS "
        "(SELECT 1 FROM org_domain_pack WHERE org_id=1 AND pack_code='party');"
    )
    add("")
    add("-- 4. 组织架构（主任办公室 + 四个业务部门，对应图示 5 个部门分组）")
    add(
        "INSERT INTO sys_department "
        "(id, org_id, parent_id, name, unit_type, code, level, path, leader_user_id, sort_order, created_at, updated_at) VALUES"
    )
    dept_id = {key: i + 1 for i, (key, *_rest) in enumerate(DEPARTMENTS)}
    user_id = {row[0]: i + 1 for i, row in enumerate(ACCOUNTS)}
    dept_rows = []
    for key, name, unit_type, sort, leader in DEPARTMENTS:
        did = dept_id[key]
        dept_rows.append(
            f"({did}, 1, NULL, {sql_str(name)}, {sql_str(unit_type)}, {sql_str(key.upper())}, "
            f"1, {sql_str('/' + str(did))}, {user_id[leader]}, {sort}, NOW(), NOW())"
        )
    add(",\n".join(dept_rows) + ";")
    add("")
    add("-- 5. 账号：70 个（68 个图示岗位 + 指导老师 + 系统管理员），密码均为 123456")
    add(
        "INSERT INTO sys_user "
        "(id, org_id, username, password, real_name, student_no, phone, dept_id, role_id, status, created_at, updated_at, deleted) VALUES"
    )
    role_id = {
        "TEACHER": 1,
        "DIRECTOR": 2,
        "ORG_LEADER": 3,
        "SECRETARY_LEADER": 4,
        "MEDIA_LEADER": 5,
        "TECH_LEADER": 6,
        "STAFF": 7,
    }
    user_rows = []
    for i, (username, real_name, dept_key, role_code, _position) in enumerate(ACCOUNTS, start=1):
        did = "NULL" if dept_key is None else str(dept_id[dept_key])
        user_rows.append(
            f"({i}, 1, {sql_str(username)}, {sql_str(PASSWORD_HASH)}, {sql_str(real_name)}, "
            f"NULL, NULL, {did}, {role_id[role_code]}, 1, NOW(), NOW(), 0)"
        )
    add(",\n".join(user_rows) + ";")
    add("")
    add("-- 5.1 作废旧 token：水位设为当前时刻，此前签发的 JWT 一律失效")
    add("UPDATE sys_user SET token_invalid_before = NOW();")
    add("")
    add("-- 6. 岗位字典")
    add(
        "INSERT INTO org_position (id, org_id, name, level, role_code, sort_order) VALUES"
    )
    add(
        ",\n".join(
            f"({i}, 1, {sql_str(name)}, {level}, {sql_str(code)}, {sort})"
            for i, (name, level, code, sort) in enumerate(POSITIONS, start=1)
        )
        + ";"
    )
    add("")
    add("-- 7. 当前届别")
    add(
        "INSERT INTO member_session (id, name, is_current, sort_order, remark, created_at, updated_at, deleted, org_id) "
        "VALUES (1, '第九届', 1, 1, NULL, NOW(), NOW(), 0, 1);"
    )
    add("")
    add("-- 7.1 成员名册：与图示 68 个岗位一一对应")
    add(
        "INSERT INTO member (id, session_id, dept_id, position, name, gender, student_no, class_name, phone, "
        "political_status, status, remark, created_by, created_at, updated_at, deleted, org_id) VALUES"
    )
    member_rows = []
    member_id = 0
    for _username, real_name, dept_key, _role_code, position in ACCOUNTS:
        code = MEMBER_POSITION.get(position)
        if code is None:
            continue
        member_id += 1
        did = "NULL" if dept_key is None else str(dept_id[dept_key])
        member_rows.append(
            f"({member_id}, 1, {did}, {sql_str(code)}, {sql_str(real_name)}, NULL, NULL, NULL, NULL, "
            f"NULL, 'ACTIVE', '演示数据', NULL, NOW(), NOW(), 0, 1)"
        )
    add(",\n".join(member_rows) + ";")
    add("")
    add("-- 8. 自增起点")
    add("ALTER TABLE organization AUTO_INCREMENT = 2;")
    add("ALTER TABLE sys_role AUTO_INCREMENT = 8;")
    add("ALTER TABLE role_revision AUTO_INCREMENT = 8;")
    add("ALTER TABLE sys_department AUTO_INCREMENT = 6;")
    add("ALTER TABLE sys_user AUTO_INCREMENT = 71;")
    add("ALTER TABLE org_position AUTO_INCREMENT = 13;")
    add("ALTER TABLE member_session AUTO_INCREMENT = 2;")
    add("ALTER TABLE member AUTO_INCREMENT = 69;")
    add("")
    add("SET FOREIGN_KEY_CHECKS = 1;")
    add("")
    add("-- 校验输出")
    add("SELECT 'organization' AS item, COUNT(*) AS n FROM organization")
    add("UNION ALL SELECT 'departments', COUNT(*) FROM sys_department")
    add("UNION ALL SELECT 'users', COUNT(*) FROM sys_user")
    add("UNION ALL SELECT 'positions', COUNT(*) FROM org_position")
    add("UNION ALL SELECT 'members', COUNT(*) FROM member")
    add("UNION ALL SELECT 'roles', COUNT(*) FROM sys_role")
    add("UNION ALL SELECT 'role_grants', COUNT(*) FROM role_grant;")
    add("")
    return "\n".join(lines)


def dept_name(key: str | None) -> str:
    if key is None:
        return "主任办公室"
    return next(name for k, name, *_ in DEPARTMENTS if k == key)


def build_markdown() -> str:
    lines: list[str] = []
    add = lines.append
    add("# 演示账号清单")
    add("")
    add(f"- **组织**：{ORG_NAME}（{ORG_SHORT}）")
    add(f"- **统一密码**：`{PASSWORD}`")
    add("- **账号总数**：70（图示 68 个岗位 + 指导老师 + 系统管理员）")
    add("- **成员名册**：第九届预置 68 人，职位与图示一致")
    add("- **组织架构**：主任办公室（中心）、青年科技部、文秘部、新媒体中心、组织部")
    add("")
    add("> 仅用于本地/演示环境，请勿在生产环境使用这些弱口令账号。")
    add("")
    add("**角色复用说明**：系统内置角色只有 7 种。副主任复用「主任」角色，副部长复用对应部门的部长角色；")
    add("精确职位（主任/副主任/部长/副部长/干事）以「成员与党务 → 成员名册」中的职位字段为准。")
    add("`admin` 复用「指导老师」角色以获得全部业务权限，组织治理权限来自「组织所有者」身份。")
    add("")
    add("## 快速登录")
    add("")
    add("| 演示场景 | 用户名 | 姓名 | 系统角色 | 部门 |")
    add("| --- | --- | --- | --- | --- |")
    quick = [
        ("系统管理员（组织所有者）", "admin"),
        ("指导老师", "teacher"),
        ("主任", "zhuren"),
        ("副主任", "fuzhuren1"),
        ("青年科技部长", "techleader"),
        ("文秘部长", "secleader"),
        ("新媒体部长", "medialeader"),
        ("组织部长", "orgleader"),
        ("干事（青年科技部）", "techstaff01"),
        ("干事（组织部）", "orgstaff16"),
    ]
    by_name = {row[0]: row for row in ACCOUNTS}
    for label, username in quick:
        _, real_name, dept_key, role_code, _ = by_name[username]
        add(f"| {label} | `{username}` | {real_name} | {ROLE_NAMES[role_code]} | {dept_name(dept_key)} |")
    add("")
    add("## 完整账号")
    add("")
    groups = []
    for key, name, *_ in DEPARTMENTS:
        groups.append((key, name, [r for r in ACCOUNTS if r[2] == key]))
    for _key, title, rows in groups:
        add(f"### {title}（{len(rows)} 人）")
        add("")
        add("| 职位 | 姓名 | 用户名 | 系统角色 | 密码 |")
        add("| --- | --- | --- | --- | --- |")
        for username, real_name, _dept, role_code, position in rows:
            add(f"| {position} | {real_name} | `{username}` | {ROLE_NAMES[role_code]} | `{PASSWORD}` |")
        add("")
    return "\n".join(lines)


def build_csv() -> str:
    import io

    buf = io.StringIO()
    writer = csv.writer(buf, lineterminator="\n")
    writer.writerow(["部门", "职位", "姓名", "用户名", "系统角色", "密码"])
    for username, real_name, dept_key, role_code, position in ACCOUNTS:
        writer.writerow([dept_name(dept_key), position, real_name, username, ROLE_NAMES[role_code], PASSWORD])
    return buf.getvalue()


def main() -> None:
    usernames = [row[0] for row in ACCOUNTS]
    names = [row[1] for row in ACCOUNTS]
    assert len(usernames) == len(set(usernames)), "duplicate username"
    assert len(names) == len(set(names)), "duplicate real name"
    for key, _name, _type, _sort, leader in DEPARTMENTS:
        assert any(row[0] == leader and row[2] == key for row in ACCOUNTS)

    (OUT_DIR / "reset-and-seed-demo.sql").write_text(build_sql(), encoding="utf-8")
    (OUT_DIR / "demo-accounts.md").write_text(build_markdown(), encoding="utf-8")
    (OUT_DIR / "demo-accounts.csv").write_text(build_csv(), encoding="utf-8-sig")
    print(f"accounts={len(ACCOUNTS)} departments={len(DEPARTMENTS)}")
    print(OUT_DIR / "reset-and-seed-demo.sql")


if __name__ == "__main__":
    main()
