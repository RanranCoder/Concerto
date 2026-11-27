package com.pams.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class ImportSchema {
    private ImportSchema() {}

    record Column(String name, String type, boolean identity) {
        boolean textual() {
            return "character varying".equals(type) || "text".equals(type) || "character".equals(type);
        }
    }

    record Table(String name, List<Column> columns, List<String> keys, Set<String> parents) {
        String target() {
            return "pams." + identifier(name, false);
        }

        String select(boolean mysql) {
            var names = columns.stream().map(column -> identifier(column.name(), mysql)).toList();
            var sort = keys.stream().map(key -> {
                var column = columns.get(index(key));
                String quoted = identifier(key, mysql);
                if (!column.textual()) return quoted;
                // PK equality uses the native collation. Reconciliation traverses both sides in UTF-8 byte order.
                return mysql ? "CAST(" + quoted + " AS BINARY)" : quoted + " COLLATE \"C\"";
            }).toList();
            return "SELECT " + String.join(",", names) + " FROM "
                    + (mysql ? identifier(name, true) : target()) + " ORDER BY " + String.join(",", sort);
        }

        String insert() {
            var names = columns.stream().map(column -> identifier(column.name(), false)).toList();
            var binders = columns.stream().map(column -> "jsonb".equals(column.type()) ? "CAST(? AS jsonb)" : "?").toList();
            return "INSERT INTO " + target() + " (" + String.join(",", names) + ") VALUES ("
                    + String.join(",", binders) + ")";
        }

        int index(String name) {
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).name().equals(name)) return i;
            }
            return -1;
        }
    }

    static List<Table> load(Connection source, Connection target) throws SQLException {
        var sourceColumns = columns(source, source.getCatalog(), true);
        var targetColumns = columns(target, "pams", false);
        if (targetColumns.isEmpty() || !sourceColumns.keySet().equals(targetColumns.keySet())) {
            throw new IllegalStateException("Table list mismatch. Source-only: "
                    + difference(sourceColumns.keySet(), targetColumns.keySet()) + ", destination-only: "
                    + difference(targetColumns.keySet(), sourceColumns.keySet()));
        }
        var tables = new TreeMap<String, Table>();
        for (var entry : targetColumns.entrySet()) {
            String name = entry.getKey();
            var expected = entry.getValue().stream().map(Column::name).collect(java.util.stream.Collectors.toSet());
            var actual = sourceColumns.get(name).stream().map(Column::name).collect(java.util.stream.Collectors.toSet());
            if (!actual.equals(expected)) {
                throw new IllegalStateException("Column list mismatch in " + name + ". Source-only: "
                        + difference(actual, expected) + ", destination-only: " + difference(expected, actual));
            }
            var keys = primaryKey(target, null, "pams", name);
            if (keys.isEmpty() || !keys.equals(primaryKey(source, source.getCatalog(), null, name))) {
                throw new IllegalStateException("Stable primary key mismatch in " + name);
            }
            var parents = new TreeSet<String>();
            try (var refs = target.getMetaData().getImportedKeys(null, "pams", name)) {
                while (refs.next()) {
                    String parent = refs.getString("PKTABLE_NAME");
                    if (name.equals(parent)) {
                        if (!"fk_task_dep".equals(refs.getString("FK_NAME"))
                                || refs.getShort("DEFERRABILITY") != java.sql.DatabaseMetaData.importedKeyInitiallyImmediate) {
                            throw new IllegalStateException("Unsupported self-referencing foreign key in " + name);
                        }
                    } else if ("pams".equals(refs.getString("PKTABLE_SCHEM"))) {
                        parents.add(parent);
                    } else {
                        throw new IllegalStateException("Unexpected foreign key outside pams in " + name);
                    }
                }
            }
            tables.put(name, new Table(name, entry.getValue(), keys, parents));
        }
        var result = new ArrayList<Table>();
        var visited = new HashSet<String>();
        var visiting = new HashSet<String>();
        for (var table : tables.values()) visit(table, tables, visited, visiting, result);
        return result;
    }

    private static void visit(Table table, Map<String, Table> tables, Set<String> visited,
                              Set<String> visiting, List<Table> result) {
        if (visited.contains(table.name())) return;
        if (!visiting.add(table.name())) throw new IllegalStateException("Foreign key cycle at " + table.name());
        for (String parent : table.parents()) visit(tables.get(parent), tables, visited, visiting, result);
        visiting.remove(table.name());
        visited.add(table.name());
        result.add(table);
    }

    private static Map<String, List<Column>> columns(Connection connection, String schema, boolean mysql)
            throws SQLException {
        var columns = new TreeMap<String, List<Column>>();
        String identity = mysql ? "'NO'" : "c.is_identity";
        String sql = "SELECT c.table_name,c.column_name,c.data_type," + identity
                + " FROM information_schema.columns c JOIN information_schema.tables t"
                + " ON t.table_schema=c.table_schema AND t.table_name=c.table_name"
                + " WHERE c.table_schema=? AND t.table_type='BASE TABLE'"
                + " AND c.table_name <> 'flyway_schema_history' ORDER BY c.table_name,c.ordinal_position";
        try (var query = connection.prepareStatement(sql)) {
            query.setString(1, schema);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    String table = rows.getString(1);
                    String column = rows.getString(2);
                    identifier(table, mysql);
                    identifier(column, mysql);
                    columns.computeIfAbsent(table, ignored -> new ArrayList<>())
                            .add(new Column(column, rows.getString(3), "YES".equals(rows.getString(4))));
                }
            }
        }
        return columns;
    }

    private static List<String> primaryKey(Connection connection, String catalog, String schema, String table)
            throws SQLException {
        var keys = new TreeMap<Short, String>();
        try (var rows = connection.getMetaData().getPrimaryKeys(catalog, schema, table)) {
            while (rows.next()) keys.put(rows.getShort("KEY_SEQ"), rows.getString("COLUMN_NAME"));
        }
        return List.copyOf(keys.values());
    }

    static String identifier(String name, boolean mysql) {
        if (!name.matches("[a-z][a-z0-9_]*")) throw new IllegalStateException("Unsupported database identifier");
        return mysql ? "`" + name + "`" : "\"" + name + "\"";
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        var result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }
}
