package io.github._98woonho.dbscout.connection;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SakilaDemoTest {

    /** MySQL 원본 Sakila와 같은 행 수여야 한다(데모·평가 정답의 기준). */
    private static final Map<String, Integer> EXPECTED_ROWS = Map.ofEntries(
            Map.entry("actor", 200),
            Map.entry("address", 603),
            Map.entry("category", 16),
            Map.entry("city", 600),
            Map.entry("country", 109),
            Map.entry("customer", 599),
            Map.entry("film", 1000),
            Map.entry("film_actor", 5462),
            Map.entry("film_category", 1000),
            Map.entry("inventory", 4581),
            Map.entry("language", 6),
            Map.entry("payment", 16049),
            Map.entry("rental", 16044),
            Map.entry("staff", 2),
            Map.entry("store", 2));

    @Test
    void extractsDemoDbWithOriginalRowCounts(@TempDir Path dir) throws Exception {
        Path db = SakilaDemo.extract(dir);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = conn.createStatement()) {
            for (var e : EXPECTED_ROWS.entrySet()) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + e.getKey())) {
                    rs.next();
                    assertThat(rs.getInt(1)).as(e.getKey()).isEqualTo(e.getValue());
                }
            }
            // 빌드 때 트리거를 지웠으므로 last_update 가 원본 값(2006년) 그대로여야 한다
            try (ResultSet rs = st.executeQuery("SELECT MAX(last_update) FROM actor")) {
                rs.next();
                assertThat(rs.getString(1)).startsWith("2006-02-15");
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger'")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    @Test
    void reusesExtractedFile(@TempDir Path dir) throws Exception {
        Path first = SakilaDemo.extract(dir);
        long modified = Files.getLastModifiedTime(first).toMillis();

        Path second = SakilaDemo.extract(dir);

        assertThat(second).isEqualTo(first);
        assertThat(Files.getLastModifiedTime(second).toMillis()).isEqualTo(modified);
        try (var files = Files.list(dir)) {
            assertThat(files).hasSize(1); // 임시 파일이 남지 않는다
        }
    }
}
