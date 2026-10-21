package db.migration;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Allow different organizations to independently use names such as 组织部. */
public class V39__department_tenant_unique extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        // Establish the tenant constraint before relaxing the legacy global constraint.
        execute(c, "CREATE UNIQUE INDEX uk_department_org_name ON sys_department(org_id, name)");
        if ("H2".equalsIgnoreCase(c.getMetaData().getDatabaseProductName())) {
            List<String> names = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT k.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE k"
                    + " JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS t ON k.CONSTRAINT_NAME=t.CONSTRAINT_NAME"
                    + " AND k.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA"
                    + " WHERE UPPER(k.TABLE_NAME)='SYS_DEPARTMENT' AND t.CONSTRAINT_TYPE='UNIQUE'"
                    + " GROUP BY k.CONSTRAINT_NAME HAVING COUNT(*)=1 AND MAX(UPPER(k.COLUMN_NAME))='NAME'")) {
                while (r.next()) names.add(r.getString(1));
            }
            for (String name : names)
                execute(c, "ALTER TABLE sys_department DROP CONSTRAINT \"" + name.replace("\"", "\"\"") + "\"");
        } else {
            Map<String, List<String>> indexes = new LinkedHashMap<>();
            try (ResultSet r = c.getMetaData().getIndexInfo(c.getCatalog(), null, "sys_department", true, false)) {
                while (r.next()) {
                    String name = r.getString("INDEX_NAME"), column = r.getString("COLUMN_NAME");
                    if (name != null && column != null) indexes.computeIfAbsent(name, k -> new ArrayList<>()).add(column);
                }
            }
            for (var entry : indexes.entrySet())
                if (entry.getValue().size() == 1 && "name".equalsIgnoreCase(entry.getValue().getFirst()))
                    execute(c, "ALTER TABLE sys_department DROP INDEX `" + entry.getKey().replace("`", "``") + "`");
        }
    }

    private void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }
}
