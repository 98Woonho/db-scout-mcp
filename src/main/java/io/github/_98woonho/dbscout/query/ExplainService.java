package io.github._98woonho.dbscout.query;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.github._98woonho.dbscout.config.DbScoutProperties;
import io.github._98woonho.dbscout.connection.ConnectionRegistry;
import io.github._98woonho.dbscout.connection.DbKind;
import io.github._98woonho.dbscout.connection.ReadOnlyConnections;
import io.github._98woonho.dbscout.guard.SqlGuard;

/**
 * 실행 계획만 본다(쿼리는 실행하지 않는다). PostgreSQL도 ANALYZE 없이 EXPLAIN만 쓴다.
 * 검사기를 통과한 SQL(주석 제거본) 앞에 EXPLAIN을 붙인다.
 */
@Component
public class ExplainService {

    /**
     * @param plan           실행 계획(줄 단위)
     * @param fullScanTables 인덱스 없이 테이블 전체를 읽는 것으로 보이는 테이블
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    public record ExplainResult(String connection, List<String> plan, List<String> fullScanTables) {
    }

    private static final int MAX_LINES = 200;
    /** "SCAN film" 은 전체 스캔, "SCAN film USING INDEX ..." 는 인덱스 순회라 제외 */
    private static final Pattern SQLITE_SCAN = Pattern.compile("^SCAN (\\S+)\\s*$");
    private static final Pattern PG_SEQ = Pattern.compile("Seq Scan on (\\S+)");
    private static final Pattern MYSQL_TREE_SCAN = Pattern.compile("Table scan on (\\S+)");

    private final ConnectionRegistry registry;
    private final ReadOnlyConnections connections;
    private final SqlGuard guard;
    private final int timeoutSeconds;

    public ExplainService(ConnectionRegistry registry, ReadOnlyConnections connections, SqlGuard guard,
                          DbScoutProperties properties) {
        this.registry = registry;
        this.connections = connections;
        this.guard = guard;
        this.timeoutSeconds = properties.limits().defaultTimeoutSeconds();
    }

    public ExplainResult explain(String connection, String sql) {
        ConnectionRegistry.Entry entry = registry.get(connection);
        SqlGuard.CheckedQuery checked = guard.check(sql, entry);
        try (Connection conn = connections.open(entry)) {
            try {
                List<String> plan = switch (entry.kind()) {
                    case SQLITE -> sqlitePlan(conn, checked.sql());
                    case POSTGRESQL -> singleColumn(conn, "EXPLAIN " + checked.sql());
                    case MYSQL -> mysqlPlan(conn, checked.sql());
                };
                if (plan.size() > MAX_LINES) {
                    plan = new ArrayList<>(plan.subList(0, MAX_LINES));
                    plan.add("…(이하 생략)");
                }
                return new ExplainResult(entry.name(), plan, fullScans(entry.kind(), plan));
            } finally {
                conn.rollback();
            }
        } catch (SQLException e) {
            throw new QueryFailedException(QueryFailedException.Kind.DB_ERROR,
                    ErrorMessages.sanitize(e.getMessage(), checked.sql()));
        }
    }

    /** SQLite: EXPLAIN QUERY PLAN 결과(id, parent, detail)를 들여쓰기한 나무 모양으로. */
    private List<String> sqlitePlan(Connection conn, String sql) throws SQLException {
        Map<Integer, Integer> depth = new HashMap<>();
        List<String> lines = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(timeoutSeconds);
            try (ResultSet rs = st.executeQuery("EXPLAIN QUERY PLAN " + sql)) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    int parent = rs.getInt("parent");
                    int d = depth.getOrDefault(parent, -1) + 1;
                    depth.put(id, d);
                    lines.add("  ".repeat(d) + rs.getString("detail"));
                }
            }
        }
        return lines;
    }

    /** MySQL 8: 나무 모양(FORMAT=TREE)을 먼저 쓰고, 안 되면 표 형태를 줄로 바꾼다. */
    private List<String> mysqlPlan(Connection conn, String sql) throws SQLException {
        try {
            List<String> tree = singleColumn(conn, "EXPLAIN FORMAT=TREE " + sql);
            List<String> lines = new ArrayList<>();
            for (String block : tree) lines.addAll(List.of(block.split("\\R")));
            return lines;
        } catch (SQLException e) {
            conn.rollback();
        }
        List<String> lines = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(timeoutSeconds);
            try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    StringBuilder line = new StringBuilder();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        String v = rs.getString(i);
                        if (v == null) continue;
                        if (!line.isEmpty()) line.append(", ");
                        line.append(md.getColumnLabel(i)).append('=').append(v);
                    }
                    lines.add(line.toString());
                }
            }
        }
        return lines;
    }

    private List<String> singleColumn(Connection conn, String sql) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(timeoutSeconds);
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) lines.add(rs.getString(1));
            }
        }
        return lines;
    }

    static List<String> fullScans(DbKind kind, List<String> plan) {
        Set<String> tables = new LinkedHashSet<>();
        for (String raw : plan) {
            String line = raw.strip();
            Matcher m = switch (kind) {
                case SQLITE -> SQLITE_SCAN.matcher(line);
                case POSTGRESQL -> PG_SEQ.matcher(line);
                case MYSQL -> MYSQL_TREE_SCAN.matcher(line);
            };
            if (m.find()) tables.add(m.group(1));
            if (kind == DbKind.MYSQL && line.toLowerCase(Locale.ROOT).contains("type=all")) {
                Matcher t = Pattern.compile("table=([^,]+)").matcher(line);
                if (t.find()) tables.add(t.group(1));
            }
        }
        return List.copyOf(tables);
    }
}
