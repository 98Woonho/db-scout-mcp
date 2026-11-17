package io.github._98woonho.dbscout.query;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github._98woonho.dbscout.config.DbScoutProperties;
import io.github._98woonho.dbscout.config.DbScoutProperties.Limits;
import io.github._98woonho.dbscout.connection.ConnectionRegistry;
import io.github._98woonho.dbscout.connection.DbKind;
import io.github._98woonho.dbscout.connection.ReadOnlyConnections;
import io.github._98woonho.dbscout.guard.SqlGuard;
import io.github._98woonho.dbscout.mask.JdbcSchemaLookup;
import io.github._98woonho.dbscout.mask.LineageAnalyzer;
import io.github._98woonho.dbscout.mask.LineageAnalyzer.MaskPlan;
import io.github._98woonho.dbscout.mask.LineageAnalyzer.OutputColumn;
import io.github._98woonho.dbscout.mask.MaskKind;
import io.github._98woonho.dbscout.mask.MaskPolicy;
import jakarta.annotation.PreDestroy;

/**
 * 검사를 통과한 SELECT를 제한을 지키며 실행한다.
 *
 * <ul>
 *   <li>안전장치 1겹째: {@link SqlGuard} 검사. 주석을 지운 SQL을 실행한다.</li>
 *   <li>2겹째: {@link ReadOnlyConnections}의 읽기 전용 연결. 끝나면 항상 롤백한다.</li>
 *   <li>3겹째: 행 수({@code setMaxRows}, MySQL은 서버 쪽에서도 제한), 실행 시간(드라이버 제한 + DB 설정 +
 *       감시 타이머의 {@code cancel()}), 셀 길이, 응답 크기 제한.</li>
 *   <li>4겹째: 실행 전에 결과 컬럼의 출처를 추적해 개인정보 컬럼을 가린다({@link LineageAnalyzer}).</li>
 * </ul>
 */
@Component
public class QueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(QueryExecutor.class);

    private final ConnectionRegistry registry;
    private final ReadOnlyConnections connections;
    private final SqlGuard guard;
    private final Limits limits;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "db-scout-query-watchdog");
        t.setDaemon(true);
        return t;
    });

    public QueryExecutor(ConnectionRegistry registry, ReadOnlyConnections connections, SqlGuard guard,
                         DbScoutProperties properties) {
        this.registry = registry;
        this.connections = connections;
        this.guard = guard;
        this.limits = properties.limits();
    }

    @PreDestroy
    void shutdown() {
        watchdog.shutdownNow();
    }

    /**
     * SQL을 검사하고 실행한다.
     *
     * @param connection     접속 이름(비우면 첫 번째)
     * @param maxRows        돌려받을 최대 행 수(비우면 기본값, 최대값을 넘으면 줄인다)
     * @param timeoutSeconds 실행 시간 제한(비우면 기본값, 최대값을 넘으면 줄인다)
     * @throws io.github._98woonho.dbscout.guard.QueryRejectedException 검사에서 막히면
     * @throws QueryFailedException 실행 중 실패하면
     */
    public QueryResult run(String connection, String sql, Integer maxRows, Integer timeoutSeconds) {
        ConnectionRegistry.Entry entry = registry.get(connection);
        SqlGuard.CheckedQuery checked = guard.check(sql, entry.kind());

        List<String> notes = new ArrayList<>();
        int rows = clamp(maxRows, limits.defaultRows(), limits.maxRows(), "행 수를", "행으", notes);
        int timeout = clamp(timeoutSeconds, limits.defaultTimeoutSeconds(), limits.maxTimeoutSeconds(),
                "실행 시간 제한을", "초", notes);

        Connection conn;
        try {
            conn = connections.open(entry);
        } catch (SQLException e) {
            log.warn("'{}' 접속 실패: {}", entry.name(), e.getMessage());
            throw new QueryFailedException(QueryFailedException.Kind.CONNECTION,
                    "'" + entry.name() + "' DB에 접속하지 못했습니다. 접속 설정과 DB 상태를 확인하세요.");
        }
        try {
            MaskPlan plan = new LineageAnalyzer(entry.maskPolicy(), new JdbcSchemaLookup(conn, entry.kind()))
                    .plan(checked.select());
            return execute(conn, entry, checked.sql(), plan, rows, timeout, notes);
        } finally {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // 읽기 전용이라 되돌릴 것이 없다
            }
            try {
                conn.close();
            } catch (SQLException ignored) {
                // 닫기 실패는 결과에 영향이 없다
            }
        }
    }

    private QueryResult execute(Connection conn, ConnectionRegistry.Entry entry, String sql, MaskPlan plan,
                                int maxRows, int timeoutSeconds, List<String> notes) {
        long started = System.nanoTime();
        AtomicBoolean timedOut = new AtomicBoolean(false);
        try {
            applyServerTimeout(conn, entry.kind(), timeoutSeconds);
            try (Statement st = conn.createStatement()) {
                st.setMaxRows(maxRows + 1); // 한 줄 더 받아 보고 잘렸는지 안다
                st.setFetchSize(Math.min(maxRows + 1, 500));
                st.setQueryTimeout(timeoutSeconds);
                ScheduledFuture<?> timer = watchdog.schedule(() -> {
                    timedOut.set(true);
                    try {
                        st.cancel();
                    } catch (SQLException ignored) {
                        // 이미 끝난 쿼리
                    }
                }, timeoutSeconds * 1000L, TimeUnit.MILLISECONDS);
                try (ResultSet rs = st.executeQuery(sql)) {
                    return read(rs, entry.maskPolicy(), plan, maxRows, notes, started, timedOut);
                } finally {
                    timer.cancel(false);
                }
            }
        } catch (SQLException e) {
            if (timedOut.get() || isTimeout(e)) {
                throw new QueryFailedException(QueryFailedException.Kind.TIMEOUT,
                        timeoutSeconds + "초 안에 끝나지 않아 중단했습니다. 조건을 좁히거나, 행을 가져오기보다 "
                                + "SQL 안에서 집계하거나, explain_query로 실행 계획을 확인하세요.");
            }
            if (isReadOnlyViolation(e)) {
                log.warn("읽기 전용 연결이 쓰기를 막았습니다(검사기가 놓친 쿼리). SQL: {}", sql);
                throw new QueryFailedException(QueryFailedException.Kind.READ_ONLY_VIOLATION,
                        "읽기 전용 연결이라 데이터를 바꾸는 작업은 실행되지 않았습니다.");
            }
            throw new QueryFailedException(QueryFailedException.Kind.DB_ERROR,
                    ErrorMessages.sanitize(e.getMessage(), sql));
        }
    }

    private QueryResult read(ResultSet rs, MaskPolicy policy, MaskPlan plan, int maxRows, List<String> notes,
                             long started, AtomicBoolean timedOut) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<String> columns = new ArrayList<>(n);
        MaskKind[] masks = new MaskKind[n];
        boolean[] derived = new boolean[n];
        List<QueryResult.MaskedColumn> masked = new ArrayList<>();
        boolean planUsable = plan.outputs() != null && plan.outputs().size() == n;
        if (!planUsable && plan.touched() != null) {
            notes.add("결과 컬럼이 어느 원본 컬럼에서 왔는지 확정하지 못해, 개인정보가 섞였을 수 있는 컬럼을 모두 가렸습니다. "
                    + "SELECT * 대신 필요한 컬럼을 직접 적으면 덜 가려집니다.");
        }
        for (int i = 0; i < n; i++) {
            String label = md.getColumnLabel(i + 1);
            columns.add(label);
            MaskKind kind;
            boolean d;
            if (planUsable) {
                OutputColumn oc = plan.outputs().get(i);
                kind = oc.kind();
                d = oc.derived();
            } else {
                kind = plan.touched();
                d = true;
            }
            MaskKind byLabel = policy.kindFor(null, label); // 별칭이 email 이어도 가린다
            if (kind == null && byLabel != null) d = false;
            kind = MaskKind.strongest(kind, byLabel);
            masks[i] = kind;
            derived[i] = d;
            if (kind != null) {
                masked.add(new QueryResult.MaskedColumn(label, d && kind != MaskKind.SECRET ? "일부 가림" : kind.label()));
            }
        }

        CellFormatter formatter = new CellFormatter(limits.maxCellChars());
        List<List<Object>> rows = new ArrayList<>();
        int size = columns.stream().mapToInt(c -> c.length() + 3).sum();
        int truncatedCells = 0;
        boolean truncated = false;
        String reason = null;
        while (rs.next()) {
            if (timedOut.get()) throw new SQLTimeoutException("watchdog");
            if (rows.size() >= maxRows) {
                truncated = true;
                reason = "행 수 제한(" + maxRows + "행). 더 있을 수 있습니다.";
                break;
            }
            List<Object> row = new ArrayList<>(n);
            int rowSize = 2;
            for (int i = 0; i < n; i++) {
                CellFormatter.Cell cell = formatter.format(rs.getObject(i + 1), masks[i], derived[i]);
                if (cell.truncated()) truncatedCells++;
                row.add(cell.value());
                rowSize += CellFormatter.jsonLength(cell.value()) + 1;
            }
            if (size + rowSize > limits.maxResponseChars() && !rows.isEmpty()) {
                truncated = true;
                reason = "응답 크기 제한(약 " + limits.maxResponseChars() + "자). 컬럼을 줄이거나 집계하세요.";
                break;
            }
            size += rowSize;
            rows.add(row);
        }
        if (truncatedCells > 0) {
            notes.add("셀 " + truncatedCells + "개가 " + limits.maxCellChars() + "자를 넘어 잘렸습니다.");
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        return new QueryResult(columns, rows, rows.size(), truncated, reason, elapsedMs,
                List.copyOf(masked), truncatedCells, List.copyOf(notes));
    }

    /** DB 쪽에도 시간 제한을 건다. 실패해도(예: MariaDB) 드라이버 제한과 감시 타이머가 남아 있다. */
    private static void applyServerTimeout(Connection conn, DbKind kind, int seconds) {
        String sql = switch (kind) {
            case POSTGRESQL -> "SET LOCAL statement_timeout = " + (seconds * 1000L);
            case MYSQL -> "SET SESSION max_execution_time = " + (seconds * 1000L);
            case SQLITE -> null;
        };
        if (sql == null) return;
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            log.debug("DB 쪽 시간 제한 설정 실패(무시): {}", e.getMessage());
        }
    }

    private static int clamp(Integer requested, int defaultValue, int max, String what, String unit, List<String> notes) {
        if (requested == null || requested <= 0) return defaultValue;
        if (requested > max) {
            notes.add(what + " 최대값 " + max + unit + "로 줄였습니다.");
            return max;
        }
        return requested;
    }

    private static boolean isTimeout(SQLException e) {
        if (e instanceof SQLTimeoutException) return true;
        String state = e.getSQLState();
        if ("57014".equals(state)) return true; // PostgreSQL: query_canceled (statement_timeout)
        if (e.getErrorCode() == 3024) return true; // MySQL: max_execution_time 초과
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
        return msg.contains("interrupted"); // SQLite: cancel() → SQLITE_INTERRUPT
    }

    private static boolean isReadOnlyViolation(SQLException e) {
        String state = e.getSQLState();
        if ("25006".equals(state)) return true; // PostgreSQL: read_only_sql_transaction
        if (e.getErrorCode() == 1792) return true; // MySQL: ER_CANT_EXECUTE_IN_READ_ONLY_TRANSACTION
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
        return msg.contains("readonly") || msg.contains("read-only") || msg.contains("query_only");
    }
}
