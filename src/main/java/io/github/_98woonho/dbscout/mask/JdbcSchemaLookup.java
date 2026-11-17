package io.github._98woonho.dbscout.mask;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github._98woonho.dbscout.connection.DbKind;

/** JDBC 메타데이터로 컬럼 목록을 찾는다. 같은 쿼리 안에서는 결과를 기억해 다시 묻지 않는다. */
public final class JdbcSchemaLookup implements SchemaLookup {

    private final Connection connection;
    private final DbKind kind;
    private final Map<String, List<String>> cache = new HashMap<>();

    public JdbcSchemaLookup(Connection connection, DbKind kind) {
        this.connection = connection;
        this.kind = kind;
    }

    @Override
    public List<String> columns(String schema, String table) {
        if (table == null) return List.of();
        String key = (schema == null ? "" : schema) + "." + table;
        return cache.computeIfAbsent(key, k -> load(unquote(schema), unquote(table)));
    }

    private List<String> load(String schema, String table) {
        // DB마다 이름의 대소문자 처리가 달라(PostgreSQL은 소문자, 일부는 대문자) 그대로·소문자·대문자 순으로 찾는다
        Set<String> tried = new LinkedHashSet<>(List.of(table, table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT)));
        for (String t : tried) {
            List<String> found = query(schema, t);
            if (!found.isEmpty()) return found;
        }
        return List.of();
    }

    private List<String> query(String schema, String table) {
        try {
            DatabaseMetaData md = connection.getMetaData();
            // MySQL은 '데이터베이스'를 catalog로, PostgreSQL은 schema로 다룬다
            String catalog = kind == DbKind.MYSQL ? schema : null;
            String schemaPattern = kind == DbKind.MYSQL ? null : schema;
            List<String> columns = new ArrayList<>();
            try (ResultSet rs = md.getColumns(catalog, schemaPattern, escape(md, table), "%")) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    if (!columns.contains(name)) columns.add(name); // 다른 스키마의 같은 이름 테이블은 합친다(보수적으로)
                }
            }
            return columns;
        } catch (SQLException e) {
            return List.of();
        }
    }

    private static String escape(DatabaseMetaData md, String name) throws SQLException {
        String esc = md.getSearchStringEscape();
        if (esc == null || esc.isEmpty()) return name;
        return name.replace(esc, esc + esc).replace("_", esc + "_").replace("%", esc + "%");
    }

    private static String unquote(String s) {
        if (s == null) return null;
        String t = s.strip();
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("`") && t.endsWith("`")
                || t.startsWith("[") && t.endsWith("]"))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}
