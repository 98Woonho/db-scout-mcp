package io.github._98woonho.dbscout.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github._98woonho.dbscout.config.DbScoutProperties.Limits;
import io.github._98woonho.dbscout.guard.QueryRejectedException;

/** 행 수·실행 시간·셀 길이·응답 크기 제한. */
class QueryLimitsTest {

    @TempDir
    static Path dir;
    static QueryExecutor executor;

    @BeforeAll
    static void setUp() {
        executor = SakilaFixture.executor(dir);
    }

    @Test
    void defaultRowLimit() {
        QueryResult r = executor.run(null, "SELECT * FROM rental", null, null);

        assertThat(r.rowCount()).isEqualTo(100);
        assertThat(r.truncated()).isTrue();
        assertThat(r.truncatedReason()).contains("행 수 제한");
    }

    @Test
    void requestedRowLimit() {
        QueryResult r = executor.run(null, "SELECT * FROM rental", 5, null);

        assertThat(r.rowCount()).isEqualTo(5);
        assertThat(r.truncated()).isTrue();
    }

    @Test
    void requestAboveMaximumIsReduced() {
        QueryResult r = executor.run(null, "SELECT rental_id FROM rental", 5000, null);

        assertThat(r.rowCount()).isEqualTo(1000);
        assertThat(r.notes()).anyMatch(n -> n.contains("1000행으로 줄였습니다"));
    }

    @Test
    void notTruncatedWhenAllRowsFit() {
        QueryResult r = executor.run(null, "SELECT * FROM category", null, null);

        assertThat(r.rowCount()).isEqualTo(16);
        assertThat(r.truncated()).isFalse();
        assertThat(r.truncatedReason()).isNull();
    }

    @Test
    void longCellsAreCut() {
        QueryResult r = executor.run(null, "SELECT replace(hex(zeroblob(300)), '00', 'ab') AS s", null, null);

        String s = (String) r.rows().getFirst().getFirst();
        assertThat(s).startsWith("abab").endsWith("…(+400자)").hasSize(200 + "…(+400자)".length());
        assertThat(r.truncatedCells()).isEqualTo(1);
    }

    @Test
    void binaryIsSummarized() {
        QueryResult r = executor.run(null, "SELECT zeroblob(2048) AS b", null, null);

        assertThat(r.rows().getFirst().getFirst()).isEqualTo("[binary 2.0KB]");
    }

    @Test
    void responseSizeLimit(@TempDir Path other) {
        var small = SakilaFixture.executor(other, new Limits(100, 1000, 10, 60, 200, 2_000));

        QueryResult r = small.run(null, "SELECT * FROM film", null, null);

        assertThat(r.truncated()).isTrue();
        assertThat(r.truncatedReason()).contains("응답 크기");
        assertThat(r.rowCount()).isBetween(1, 20);
    }

    @Test
    void endlessQueryIsStoppedAtTimeLimit() {
        long started = System.nanoTime();
        QueryFailedException e = catchThrowableOfType(QueryFailedException.class, () -> executor.run(null,
                "WITH RECURSIVE r(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM r) SELECT COUNT(*) FROM r", null, 1));
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(e).isNotNull();
        assertThat(e.kind()).isEqualTo(QueryFailedException.Kind.TIMEOUT);
        assertThat(e.getMessage()).contains("1초 안에 끝나지 않아");
        assertThat(took).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void hugeValuesAreRefusedBySqlite() {
        // SQLite는 서버 안에서 실행되므로, 거대한 값으로 메모리를 고갈시키지 못하게 값 크기를 제한했다
        QueryFailedException e = catchThrowableOfType(QueryFailedException.class,
                () -> executor.run(null, "SELECT length(randomblob(100000000))", null, null));

        assertThat(e).isNotNull();
        assertThat(e.kind()).isEqualTo(QueryFailedException.Kind.DB_ERROR);
        assertThat(e.getMessage()).containsIgnoringCase("too big");
    }

    @Test
    void writesAreRejectedBeforeExecution() {
        QueryRejectedException e = catchThrowableOfType(QueryRejectedException.class,
                () -> executor.run(null, "DELETE FROM actor", null, null));

        assertThat(e).isNotNull();
    }

    @Test
    void reportsElapsedTimeAndColumns() {
        QueryResult r = executor.run(null, "SELECT actor_id, first_name FROM actor ORDER BY actor_id LIMIT 2", null, null);

        assertThat(r.columns()).containsExactly("actor_id", "first_name");
        assertThat(r.rows()).hasSize(2);
        assertThat(r.elapsedMs()).isGreaterThanOrEqualTo(0);
    }
}
