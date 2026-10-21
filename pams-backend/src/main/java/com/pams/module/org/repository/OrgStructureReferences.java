package com.pams.module.org.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Include archived/soft-deleted records: deleting a node must not orphan historical references. */
@Repository
public class OrgStructureReferences {
    private final JdbcTemplate jdbc;

    public OrgStructureReferences(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean hasAccounts(Long id) {
        return exists("sys_user", "dept_id", id);
    }

    public boolean hasHistory(Long orgId, Long id, String name) {
        for (String table : new String[]{"member", "task", "material", "schedule", "no_class_schedule"})
            if (exists(table, "dept_id", id)) return true;
        for (String table : new String[]{"kb_document", "kb_chunk", "kb_snapshot"})
            if (exists(table, "owner_dept_id", id)) return true;
        if (exists("activity", "owner_unit_id", id) || exists("notification", "recipient_dept_id", id)
                || exists("org_invitation", "org_unit_id", id)) return true;
        return jdbc.queryForObject("SELECT COUNT(*) FROM group_chat_department WHERE org_id=? AND department=?",
                Long.class, orgId, name) > 0;
    }

    public void renameDepartmentLabels(Long orgId, Long id, String oldName, String newName) {
        jdbc.update("UPDATE group_chat_department SET department=? WHERE org_id=? AND department=?",
                newName, orgId, oldName);
        jdbc.update("UPDATE no_class_schedule SET dept_name=? WHERE org_id=? AND dept_id=?", newName, orgId, id);
    }

    private boolean exists(String table, String column, Long id) {
        // Table and column names above are fixed application constants, never request input.
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Long.class, id) > 0;
    }
}
