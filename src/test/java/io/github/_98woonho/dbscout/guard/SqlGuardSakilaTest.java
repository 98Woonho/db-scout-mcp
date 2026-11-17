package io.github._98woonho.dbscout.guard;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github._98woonho.dbscout.connection.DbKind;
import io.github._98woonho.dbscout.connection.SakilaDemo;

/**
 * 검사를 통과한 SQL은 주석을 지운 형태로 실행된다.
 * 지운 결과가 실제 DB에서 그대로 실행되는지 Sakila(SQLite)로 확인한다.
 * MySQL·PostgreSQL은 CI의 통합 테스트에서 같은 방식으로 확인한다.
 */
class SqlGuardSakilaTest {

    @TempDir
    static Path dir;
    private static Connection conn;
    private final SqlGuard guard = new SqlGuard();

    @BeforeAll
    static void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite:" + SakilaDemo.extract(dir));
    }

    @AfterAll
    static void close() throws Exception {
        if (conn != null) conn.close();
    }

    static Stream<Arguments> sqliteAllowed() {
        return Stream.of(SqlGuardTest.ALLOWED)
                .filter(c -> c.kinds().contains(DbKind.SQLITE))
                .map(c -> Arguments.argumentSet(c.title(), c.sql()));
    }

    @ParameterizedTest
    @MethodSource("sqliteAllowed")
    void cleanedSqlRunsOnSakila(String sql) {
        String cleaned = guard.check(sql, DbKind.SQLITE).sql();

        assertThatCode(() -> {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(cleaned)) {
                rs.next();
            }
        }).as(cleaned).doesNotThrowAnyException();
    }
}
