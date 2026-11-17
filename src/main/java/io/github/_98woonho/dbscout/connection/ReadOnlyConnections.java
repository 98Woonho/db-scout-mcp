package io.github._98woonho.dbscout.connection;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteLimits;
import org.springframework.stereotype.Component;

/**
 * 안전장치 2겹째: DB 연결 자체를 읽기 전용으로 연다.
 *
 * <p>SQL 검사기(1겹째)를 통과한 쿼리라도, 혹시 놓친 쓰기가 있으면 DB가 거부하게 만든다.
 * 조회가 끝나면 호출하는 쪽에서 항상 롤백하고 닫는다.
 *
 * <ul>
 *   <li>SQLite: 파일을 읽기 전용으로 열고 {@code query_only}를 켠다. 다른 DB 파일 붙이기(ATTACH)를 0개로 제한하고,
 *       값 하나의 최대 크기를 줄여 거대한 문자열로 메모리를 고갈시키지 못하게 한다.</li>
 *   <li>MySQL: 세션을 READ ONLY 트랜잭션으로 바꾸고, 여러 문장 실행·로컬 파일 읽기 옵션을 끈다.</li>
 *   <li>PostgreSQL: 세션 기본 트랜잭션을 READ ONLY로 바꾼다.</li>
 * </ul>
 */
@Component
public class ReadOnlyConnections {

    static final int CONNECT_TIMEOUT_SECONDS = 10;
    static final int SQLITE_MAX_VALUE_BYTES = 10 * 1024 * 1024;

    public Connection open(ConnectionRegistry.Entry entry) throws SQLException {
        Connection conn = switch (entry.kind()) {
            case SQLITE -> openSqlite(entry.url());
            case MYSQL -> openMysql(entry);
            case POSTGRESQL -> openPostgres(entry);
        };
        try {
            conn.setAutoCommit(false); // 트랜잭션 안에서 실행하고 끝나면 롤백한다
            return conn;
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
    }

    private static Connection openSqlite(String url) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.enableLoadExtension(false);
        config.setBusyTimeout(5_000);
        Connection conn = config.createConnection(url);
        try {
            SQLiteConnection sqlite = conn.unwrap(SQLiteConnection.class);
            sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_ATTACHED, 0);
            sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_LENGTH, SQLITE_MAX_VALUE_BYTES);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA query_only = 1"); // URL 옵션으로 읽기 전용이 풀려도 쓰기를 막는다
            }
            return conn;
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
    }

    private static Connection openMysql(ConnectionRegistry.Entry entry) throws SQLException {
        Properties props = credentials(entry);
        props.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS * 1000));
        props.setProperty("allowMultiQueries", "false");
        props.setProperty("allowLoadLocalInfile", "false");
        props.setProperty("allowUrlInLocalInfile", "false");
        props.setProperty("autoDeserialize", "false");
        props.setProperty("readOnlyPropagatesToServer", "true");
        Connection conn = DriverManager.getConnection(entry.url(), props);
        try {
            conn.setReadOnly(true);
            try (Statement st = conn.createStatement()) {
                st.execute("SET SESSION TRANSACTION READ ONLY");
            }
            return conn;
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
    }

    private static Connection openPostgres(ConnectionRegistry.Entry entry) throws SQLException {
        Properties props = credentials(entry);
        props.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
        props.setProperty("readOnly", "true");
        Connection conn = DriverManager.getConnection(entry.url(), props);
        try {
            try (Statement st = conn.createStatement()) {
                st.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
            }
            conn.setReadOnly(true);
            return conn;
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
    }

    private static Properties credentials(ConnectionRegistry.Entry entry) {
        Properties props = new Properties();
        if (entry.username() != null && !entry.username().isBlank()) props.setProperty("user", entry.username());
        if (entry.password() != null && !entry.password().isEmpty()) props.setProperty("password", entry.password());
        return props;
    }
}
