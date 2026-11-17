package io.github._98woonho.dbscout.schema;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github._98woonho.dbscout.connection.ConnectionRegistry;
import io.github._98woonho.dbscout.connection.DbKind;
import io.github._98woonho.dbscout.connection.Identifiers;
import io.github._98woonho.dbscout.connection.ReadOnlyConnections;
import io.github._98woonho.dbscout.mask.MaskKind;
import io.github._98woonho.dbscout.query.QueryFailedException;
import io.github._98woonho.dbscout.schema.SchemaModel.Column;
import io.github._98woonho.dbscout.schema.SchemaModel.ForeignKey;
import io.github._98woonho.dbscout.schema.SchemaModel.Index;
import io.github._98woonho.dbscout.schema.SchemaModel.Table;

/**
 * DB 구조(테이블, 컬럼, 인덱스, 외래키)를 JDBC 메타데이터로 읽는다.
 *
 * <p>구조는 자주 바뀌지 않으므로 접속별로 5분간 기억한다. 시스템 테이블과 설정에서 숨긴 테이블은 보여 주지 않는다.
 * 모든 조회는 읽기 전용 연결로 한다.
 */
@Component
public class SchemaService {

    private static final Logger log = LoggerFactory.getLogger(SchemaService.class);
    static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "information_schema", "pg_catalog", "pg_toast", "mysql", "sys", "performance_schema");

    /** 행 수. exact가 false면 DB 통계의 추정치. */
    public record RowCount(Long rows, boolean exact) {
    }

    /** 접속 하나의 구조 스냅숏. 외래키·전체 컬럼은 필요할 때 채운다. */
    private static final class Snapshot {
        final Instant loadedAt = Instant.now();
        final List<Table> tables;
        final Map<String, Long> rowCounts;
        final boolean rowCountsExact;
        volatile List<ForeignKey> foreignKeys;
        volatile Map<String, List<Column>> columns;

        Snapshot(List<Table> tables, Map<String, Long> rowCounts, boolean exact) {
            this.tables = tables;
            this.rowCounts = rowCounts;
            this.rowCountsExact = exact;
        }
    }

    @FunctionalInterface
    private interface ConnectionWork<T> {
        T apply(Connection conn) throws SQLException;
    }

    private final ConnectionRegistry registry;
    private final ReadOnlyConnections connections;
    private final Map<String, Snapshot> cache = new ConcurrentHashMap<>();

    public SchemaService(ConnectionRegistry registry, ReadOnlyConnections connections) {
        this.registry = registry;
        this.connections = connections;
    }

    public ConnectionRegistry.Entry entry(String connection) {
        return registry.get(connection);
    }

    // ───────────────────────── 테이블 목록 ─────────────────────────

    public List<Table> tables(ConnectionRegistry.Entry entry) {
        return snapshot(entry).tables;
    }

    public RowCount rowCount(ConnectionRegistry.Entry entry, Table table) {
        Snapshot s = snapshot(entry);
        return new RowCount(s.rowCounts.get(key(table)), s.rowCountsExact);
    }

    public boolean rowCountsExact(ConnectionRegistry.Entry entry) {
        return snapshot(entry).rowCountsExact;
    }

    /**
     * 이름으로 테이블을 찾는다. 대소문자·따옴표를 무시하고, {@code 스키마.테이블}도 받는다.
     *
     * @throws IllegalArgumentException 없거나 여러 스키마에 같은 이름이 있으면(비슷한 이름을 알려 준다)
     */
    public Table resolve(ConnectionRegistry.Entry entry, String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("테이블 이름이 비어 있습니다.");
        String[] parts = name.strip().split("\\.");
        String wanted = Identifiers.normalize(parts[parts.length - 1]);
        String schema = parts.length >= 2 ? Identifiers.normalize(parts[parts.length - 2]) : null;
        List<Table> all = tables(entry);
        List<Table> matches = all.stream()
                .filter(t -> Identifiers.normalize(t.name()).equals(wanted))
                .filter(t -> schema == null || (t.schema() != null && Identifiers.normalize(t.schema()).equals(schema)))
                .toList();
        if (matches.size() == 1) return matches.getFirst();
        if (matches.size() > 1) {
            throw new IllegalArgumentException("'" + name + "' 테이블이 여러 스키마에 있습니다: "
                    + String.join(", ", matches.stream().map(Table::qualifiedName).toList())
                    + ". 스키마를 붙여 주세요.");
        }
        List<String> similar = all.stream()
                .map(Table::name)
                .filter(n -> similar(Identifiers.normalize(n), wanted))
                .limit(5)
                .toList();
        throw new IllegalArgumentException("'" + name + "' 테이블이 없습니다."
                + (similar.isEmpty() ? " list_tables 로 목록을 확인하세요." : " 비슷한 이름: " + String.join(", ", similar)));
    }

    // ───────────────────────── 테이블 상세 ─────────────────────────

    public List<Column> columns(ConnectionRegistry.Entry entry, Table table) {
        Snapshot s = snapshot(entry);
        Map<String, List<Column>> all = allColumns(entry, s);
        List<Column> cols = all.get(key(table));
        if (cols == null) return List.of();
        return withPrimaryKeys(entry, table, cols);
    }

    public List<Index> indexes(ConnectionRegistry.Entry entry, Table table) {
        return withConnection(entry, conn -> {
            DatabaseMetaData md = conn.getMetaData();
            Map<String, Index> byName = new LinkedHashMap<>();
            try (ResultSet rs = md.getIndexInfo(catalog(entry, table), schema(entry, table), table.name(), false, true)) {
                while (rs.next()) {
                    if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                    String name = rs.getString("INDEX_NAME");
                    String column = rs.getString("COLUMN_NAME");
                    if (name == null || column == null) continue;
                    boolean unique = !rs.getBoolean("NON_UNIQUE");
                    Index idx = byName.computeIfAbsent(name, n -> new Index(n, new ArrayList<>(), unique));
                    idx.columns().add(column);
                }
            }
            return List.copyOf(byName.values());
        });
    }

    /** 이 테이블이 다른 테이블을 가리키는 외래키. */
    public List<ForeignKey> outgoing(ConnectionRegistry.Entry entry, Table table) {
        String k = table.qualifiedName();
        return foreignKeys(entry).stream().filter(fk -> fk.fromTable().equals(k)).toList();
    }

    /** 다른 테이블이 이 테이블을 가리키는 외래키. */
    public List<ForeignKey> incoming(ConnectionRegistry.Entry entry, Table table) {
        String k = table.qualifiedName();
        return foreignKeys(entry).stream().filter(fk -> fk.toTable().equals(k)).toList();
    }

    /** 보이는 모든 테이블의 외래키(숨긴 테이블과 이어진 것은 뺀다). */
    public List<ForeignKey> foreignKeys(ConnectionRegistry.Entry entry) {
        Snapshot s = snapshot(entry);
        if (s.foreignKeys == null) {
            s.foreignKeys = withConnection(entry, conn -> loadForeignKeys(entry, conn, s.tables));
        }
        return s.foreignKeys;
    }

    /** 보이는 모든 테이블의 컬럼(검색용). 키: 테이블의 qualifiedName 소문자. */
    public Map<Table, List<Column>> allColumns(ConnectionRegistry.Entry entry) {
        Snapshot s = snapshot(entry);
        Map<String, List<Column>> all = allColumns(entry, s);
        Map<Table, List<Column>> result = new LinkedHashMap<>();
        for (Table t : s.tables) result.put(t, all.getOrDefault(key(t), List.of()));
        return result;
    }

    // ───────────────────────── 읽기 ─────────────────────────

    private Snapshot snapshot(ConnectionRegistry.Entry entry) {
        Snapshot s = cache.get(entry.name());
        if (s != null && s.loadedAt.plus(CACHE_TTL).isAfter(Instant.now())) return s;
        Snapshot fresh = withConnection(entry, conn -> {
            List<Table> tables = loadTables(entry, conn);
            boolean exact = entry.kind() == DbKind.SQLITE;
            Map<String, Long> counts = exact ? countSqliteRows(conn, tables) : estimateRows(entry.kind(), conn);
            return new Snapshot(tables, counts, exact);
        });
        cache.put(entry.name(), fresh);
        return fresh;
    }

    private List<Table> loadTables(ConnectionRegistry.Entry entry, Connection conn) throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        String catalog = entry.kind() == DbKind.MYSQL ? conn.getCatalog() : null;
        List<Table> tables = new ArrayList<>();
        try (ResultSet rs = md.getTables(catalog, null, "%", null)) {
            while (rs.next()) {
                String type = upper(rs.getString("TABLE_TYPE"));
                if (type == null || type.startsWith("SYSTEM") || type.contains("TEMPORARY") || type.contains("INDEX")
                        || type.contains("SEQUENCE") || type.contains("TYPE")
                        || !(type.contains("TABLE") || type.contains("VIEW"))) {
                    continue;
                }
                String schema = entry.kind() == DbKind.MYSQL ? rs.getString("TABLE_CAT") : rs.getString("TABLE_SCHEM");
                if (entry.kind() == DbKind.SQLITE) schema = null;
                String name = rs.getString("TABLE_NAME");
                if (schema != null && SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) continue;
                if (entry.kind() == DbKind.SQLITE && name.toLowerCase(Locale.ROOT).startsWith("sqlite_")) continue;
                if (entry.isHidden(schema, name)) continue;
                String comment = blankToNull(rs.getString("REMARKS"));
                tables.add(new Table(schema, name, type.equals("BASE TABLE") ? "TABLE" : type, comment));
            }
        }
        tables.sort(Comparator.comparing((Table t) -> t.schema() == null ? "" : t.schema()).thenComparing(Table::name));
        return List.copyOf(tables);
    }

    /** SQLite는 파일 안의 작은 DB가 대부분이라 정확히 센다(테이블당 2초 제한). */
    private static Map<String, Long> countSqliteRows(Connection conn, List<Table> tables) {
        Map<String, Long> counts = new HashMap<>();
        for (Table t : tables) {
            if (t.isView() || counts.size() >= 300) continue;
            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(2);
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + Identifiers.quote(DbKind.SQLITE, t.name()))) {
                    if (rs.next()) counts.put(key(t), rs.getLong(1));
                }
            } catch (SQLException e) {
                log.debug("행 수 세기 실패 {}: {}", t.name(), e.getMessage());
            }
        }
        return counts;
    }

    /** MySQL·PostgreSQL은 통계의 추정치를 쓴다(큰 테이블을 세면 오래 걸린다). */
    private static Map<String, Long> estimateRows(DbKind kind, Connection conn) {
        String sql = kind == DbKind.POSTGRESQL
                ? "SELECT n.nspname, c.relname, c.reltuples::bigint FROM pg_class c "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.relkind IN ('r', 'p', 'm')"
                : "SELECT table_schema, table_name, table_rows FROM information_schema.tables "
                        + "WHERE table_type = 'BASE TABLE'";
        Map<String, Long> counts = new HashMap<>();
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(10);
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    long rows = rs.getLong(3);
                    if (rs.wasNull() || rows < 0) continue; // 통계가 아직 없음
                    counts.put(key(rs.getString(1), rs.getString(2)), rows);
                }
            }
        } catch (SQLException e) {
            log.debug("행 수 추정 실패: {}", e.getMessage());
        }
        return counts;
    }

    private Map<String, List<Column>> allColumns(ConnectionRegistry.Entry entry, Snapshot s) {
        if (s.columns == null) {
            s.columns = withConnection(entry, conn -> loadColumns(entry, conn, s.tables));
        }
        return s.columns;
    }

    private static Map<String, List<Column>> loadColumns(ConnectionRegistry.Entry entry, Connection conn,
                                                         List<Table> tables) throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        Map<String, Table> visible = new HashMap<>();
        for (Table t : tables) visible.put(key(t), t);
        Map<String, List<Column>> result = new HashMap<>();
        if (entry.kind() == DbKind.SQLITE) {
            // SQLite 드라이버는 테이블 이름 '%'를 지원하지만 뷰 컬럼은 테이블별로 묻는 편이 확실하다
            for (Table t : tables) {
                try (ResultSet rs = md.getColumns(null, null, t.name(), "%")) {
                    readColumns(entry, rs, visible, result, t);
                }
            }
        } else {
            String catalog = entry.kind() == DbKind.MYSQL ? conn.getCatalog() : null;
            try (ResultSet rs = md.getColumns(catalog, null, "%", "%")) {
                readColumns(entry, rs, visible, result, null);
            }
        }
        return result;
    }

    private static void readColumns(ConnectionRegistry.Entry entry, ResultSet rs, Map<String, Table> visible,
                                    Map<String, List<Column>> result, Table fixed) throws SQLException {
        while (rs.next()) {
            Table t = fixed;
            if (t == null) {
                String schema = entry.kind() == DbKind.MYSQL ? rs.getString("TABLE_CAT") : rs.getString("TABLE_SCHEM");
                t = visible.get(key(schema, rs.getString("TABLE_NAME")));
                if (t == null) continue;
            }
            String name = rs.getString("COLUMN_NAME");
            String type = rs.getString("TYPE_NAME");
            int size = rs.getInt("COLUMN_SIZE");
            if (type != null && size > 0 && size < 100_000 && type.toUpperCase(Locale.ROOT).matches(".*(CHAR|BINARY)")) {
                type = type + "(" + size + ")";
            }
            boolean nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
            String def = blankToNull(rs.getString("COLUMN_DEF"));
            if ("NULL".equalsIgnoreCase(def)) def = null; // 기본값 없음과 같다
            String comment = blankToNull(rs.getString("REMARKS"));
            MaskKind mask = entry.maskPolicy().kindFor(t.name(), name);
            result.computeIfAbsent(key(t), k -> new ArrayList<>())
                    .add(new Column(name, type, nullable, def, null, comment, mask == null ? null : mask.label()));
        }
    }

    private List<Column> withPrimaryKeys(ConnectionRegistry.Entry entry, Table table, List<Column> cols) {
        if (table.isView()) return cols;
        Set<String> pk = withConnection(entry, conn -> {
            Set<String> names = new java.util.HashSet<>();
            try (ResultSet rs = conn.getMetaData().getPrimaryKeys(catalog(entry, table), schema(entry, table), table.name())) {
                while (rs.next()) names.add(rs.getString("COLUMN_NAME"));
            }
            return names;
        });
        return cols.stream()
                .map(c -> new Column(c.name(), c.type(), c.nullable(), c.defaultValue(),
                        pk.contains(c.name()) ? Boolean.TRUE : null, c.comment(), c.masked()))
                .toList();
    }

    private static List<ForeignKey> loadForeignKeys(ConnectionRegistry.Entry entry, Connection conn, List<Table> tables)
            throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        Map<String, Table> visible = new HashMap<>();
        for (Table t : tables) visible.put(key(t), t);
        List<ForeignKey> result = new ArrayList<>();
        for (Table t : tables) {
            if (t.isView()) continue;
            try (ResultSet rs = md.getImportedKeys(catalog(entry, t), schema(entry, t), t.name())) {
                String name = null;
                Table target = null;
                List<String> from = null;
                List<String> to = null;
                while (rs.next()) {
                    if (rs.getShort("KEY_SEQ") == 1) {
                        if (from != null && target != null) {
                            result.add(new ForeignKey(name, t.qualifiedName(), from, target.qualifiedName(), to));
                        }
                        name = blankToNull(rs.getString("FK_NAME"));
                        String pkSchema = entry.kind() == DbKind.MYSQL ? rs.getString("PKTABLE_CAT") : rs.getString("PKTABLE_SCHEM");
                        if (entry.kind() == DbKind.SQLITE) pkSchema = null;
                        target = visible.get(key(pkSchema, rs.getString("PKTABLE_NAME")));
                        from = new ArrayList<>();
                        to = new ArrayList<>();
                    }
                    from.add(rs.getString("FKCOLUMN_NAME"));
                    to.add(rs.getString("PKCOLUMN_NAME"));
                }
                if (from != null && target != null) {
                    result.add(new ForeignKey(name, t.qualifiedName(), from, target.qualifiedName(), to));
                }
            }
        }
        return List.copyOf(result);
    }

    private <T> T withConnection(ConnectionRegistry.Entry entry, ConnectionWork<T> work) {
        try (Connection conn = connections.open(entry)) {
            try {
                return work.apply(conn);
            } finally {
                conn.rollback();
            }
        } catch (SQLException e) {
            log.warn("'{}' 구조 조회 실패: {}", entry.name(), e.getMessage());
            throw new QueryFailedException(QueryFailedException.Kind.CONNECTION,
                    "'" + entry.name() + "' DB의 구조를 읽지 못했습니다. 접속 설정과 권한을 확인하세요.");
        }
    }

    private static String catalog(ConnectionRegistry.Entry entry, Table t) {
        return entry.kind() == DbKind.MYSQL ? t.schema() : null;
    }

    private static String schema(ConnectionRegistry.Entry entry, Table t) {
        return entry.kind() == DbKind.MYSQL ? null : t.schema();
    }

    static String key(Table t) {
        return key(t.schema(), t.name());
    }

    static String key(String schema, String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return schema == null ? n : schema.toLowerCase(Locale.ROOT) + "." + n;
    }

    private static boolean similar(String candidate, String wanted) {
        if (candidate.contains(wanted) || wanted.contains(candidate)) return true;
        return levenshtein(candidate, wanted) <= Math.max(1, wanted.length() / 4);
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
