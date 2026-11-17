package io.github._98woonho.dbscout.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github._98woonho.dbscout.config.DbScoutProperties;
import io.github._98woonho.dbscout.mask.MaskPolicy;

/**
 * 안전장치 2겹째: SQL 검사기를 거치지 않고 연결에 직접 쓰기를 시도해도 DB가 거부해야 한다.
 * (MySQL·PostgreSQL은 CI 통합 테스트에서 확인)
 */
class ReadOnlyConnectionsTest {

    @TempDir
    Path dir;

    private Connection open() throws SQLException {
        Path db = SakilaDemo.extract(dir);
        var entry = new ConnectionRegistry.Entry("sakila", DbKind.SQLITE, "", true, "jdbc:sqlite:" + db,
                null, null, MaskPolicy.defaults(), java.util.Set.of());
        return new ReadOnlyConnections().open(entry);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM actor",
            "UPDATE film SET rental_rate = 0",
            "INSERT INTO category (name) VALUES ('x')",
            "CREATE TABLE t (a INT)",
            "DROP TABLE actor",
            "ATTACH DATABASE ':memory:' AS m",
    })
    void databaseRefusesWritesEvenWithoutTheGuard(String sql) throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            assertThatThrownBy(() -> st.execute(sql)).isInstanceOf(SQLException.class);
            conn.rollback();
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM actor")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(200);
            }
        }
    }

    @Test
    void turningOffQueryOnlyStillCannotWrite() throws Exception {
        // PRAGMA 는 SQL 검사기가 막지만, 뚫렸다고 가정해도 파일 자체가 읽기 전용으로 열려 있다
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("PRAGMA query_only = 0");
            assertThatThrownBy(() -> st.executeUpdate("DELETE FROM actor"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("readonly");
        }
    }

    @Test
    void readsStillWork() throws Exception {
        try (Connection conn = open(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA query_only")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(conn.getAutoCommit()).isFalse();
        }
    }

    @Test
    void rejectsDangerousUrlOptions() {
        var limits = new DbScoutProperties.Limits(100, 1000, 10, 60, 200, 50000);
        for (String url : List.of(
                "jdbc:mysql://localhost/shop?allowMultiQueries=true",
                "jdbc:mysql://localhost/shop?useSSL=false&allowLoadLocalInfile=true",
                "jdbc:postgresql://localhost/shop?socketFactory=evil.Factory")) {
            var props = new DbScoutProperties(
                    List.of(DbScoutProperties.ConnectionConfig.of("x", url, null, null, null)), limits);
            assertThatThrownBy(() -> new ConnectionRegistry(props, () -> "unused"))
                    .as(url)
                    .hasMessageContaining("보안을 위해");
        }
    }
}
