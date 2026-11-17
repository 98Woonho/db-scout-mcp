package io.github._98woonho.dbscout.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github._98woonho.dbscout.connection.DbKind;
import io.github._98woonho.dbscout.guard.QueryRejectedException;
import io.github._98woonho.dbscout.guard.SqlGuardTest;
import io.github._98woonho.dbscout.query.QueryFailedException;
import io.github._98woonho.dbscout.query.QueryResult;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 MySQL·PostgreSQL에서 SQLite와 같은 약속이 지켜지는지 확인한다.
 * 하위 클래스가 DB 종류와 접속 정보를 정한다. 접속 정보 환경변수가 없으면 건너뛴다(CI에서는 필수).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class DatabaseIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    abstract IntegrationStack stack();

    abstract DbKind kind();

    /**
     * 아주 오래 걸리는 쿼리. 16,049행을 세 번 곱한 4조 행에서 조건을 하나씩 계산해야 한다.
     * (조건 없이 COUNT(*)만 하면 MySQL 8은 행을 세지 않고 곱셈으로 바로 답한다)
     */
    static final String ENDLESS = "SELECT COUNT(*) FROM payment a CROSS JOIN payment b CROSS JOIN payment c "
            + "WHERE a.amount + b.amount + c.amount < 0";

    private QueryResult run(String sql) {
        return stack().executor().run(null, sql, null, null);
    }

    private Object single(String sql) {
        return run(sql).rows().getFirst().getFirst();
    }

    private static JsonNode json(CallToolResult r) {
        String text = r.content().stream().filter(TextContent.class::isInstance)
                .map(c -> ((TextContent) c).text()).reduce("", String::concat);
        assertThat(r.isError()).as(text).isFalse();
        return JSON.readTree(text);
    }

    // ───────── Sakila가 SQLite판과 같은 데이터인지 ─────────

    @ParameterizedTest
    @ValueSource(strings = {"actor:200", "film:1000", "customer:599", "rental:16044", "payment:16049", "category:16"})
    void sakilaIsLoaded(String tableAndCount) {
        String[] p = tableAndCount.split(":");
        assertThat(((Number) single("SELECT COUNT(*) FROM " + p[0])).longValue()).isEqualTo(Long.parseLong(p[1]));
    }

    // ───────── 2겹째: 읽기 전용 연결 (관리자 계정이어도) ─────────

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE actor SET first_name = 'X' WHERE actor_id = 1",
            "INSERT INTO category (name) VALUES ('probe')",
            "DELETE FROM payment WHERE payment_id = -1",
            "CREATE TABLE scout_probe (a INT)",
    })
    void databaseRefusesWritesEvenForAdminAccount(String sql) throws Exception {
        try (Connection conn = stack().connections().open(stack().entry())) {
            try (Statement st = conn.createStatement()) {
                assertThatThrownBy(() -> st.execute(sql)).isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(isReadOnlyError((SQLException) e)).as(e.getMessage()).isTrue());
            }
            conn.rollback();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT first_name FROM actor WHERE actor_id = 1")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("PENELOPE");
            }
            conn.rollback();
        }
    }

    /**
     * PostgreSQL은 서버가 25006(read_only_sql_transaction)으로 거부한다.
     * MySQL은 드라이버(Connector/J)가 먼저 "Connection is read-only"로 거부하거나, 서버가 1792로 거부한다.
     * 서버 쪽 세션 설정은 {@link #serverSessionIsReadOnly()}에서 따로 확인한다.
     */
    private boolean isReadOnlyError(SQLException e) {
        if (kind() == DbKind.POSTGRESQL) return "25006".equals(e.getSQLState());
        return e.getErrorCode() == 1792 || String.valueOf(e.getMessage()).contains("read-only");
    }

    @Test
    void serverSessionIsReadOnly() throws Exception {
        // 드라이버의 검사와 별개로, DB 서버의 세션 자체가 읽기 전용이어야 한다
        String sql = kind() == DbKind.POSTGRESQL ? "SHOW transaction_read_only" : "SELECT @@SESSION.transaction_read_only";
        try (Connection conn = stack().connections().open(stack().entry());
             Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            assertThat(rs.getString(1)).isIn("on", "1");
            conn.rollback();
        }
    }

    // ───────── 3겹째: 시간 제한 ─────────

    @Test
    void longQueryIsStoppedAtTimeLimit() {
        long started = System.nanoTime();
        QueryFailedException e = catchThrowableOfType(QueryFailedException.class,
                () -> stack().executor().run(null, ENDLESS, null, 2));
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(e).isNotNull();
        assertThat(e.kind()).isEqualTo(QueryFailedException.Kind.TIMEOUT);
        assertThat(took).isLessThan(Duration.ofSeconds(10));
        // 중단된 뒤에도 다음 조회는 정상
        assertThat(((Number) single("SELECT COUNT(*) FROM actor")).intValue()).isEqualTo(200);
    }

    @Test
    void rowLimitIsApplied() {
        QueryResult r = stack().executor().run(null, "SELECT * FROM rental", 7, null);

        assertThat(r.rowCount()).isEqualTo(7);
        assertThat(r.truncated()).isTrue();
    }

    // ───────── 1겹째: 검사기를 통과한 SQL이 실제 DB에서 그대로 실행되는지 ─────────

    Stream<Arguments> allowedCases() {
        return Stream.of(SqlGuardTest.ALLOWED)
                .filter(c -> c.kinds().contains(kind()))
                .map(c -> Arguments.argumentSet(c.title(), c.sql()));
    }

    @ParameterizedTest
    @MethodSource("allowedCases")
    void allowedQueriesRunOnRealDatabase(String sql) {
        assertThat(run(sql).columns()).isNotEmpty();
    }

    @Test
    void writesAreRejectedBeforeReachingTheDatabase() {
        assertThatThrownBy(() -> run("DELETE FROM payment")).isInstanceOf(QueryRejectedException.class);
    }

    // ───────── 4겹째: 개인정보 가리기 ─────────

    @Test
    void personalColumnsAreMasked() {
        QueryResult r = run("SELECT * FROM customer WHERE customer_id = 1");

        List<Object> row = r.rows().getFirst();
        assertThat(row.get(r.columns().indexOf("email"))).isEqualTo("M***@sakilacustomer.org");
        assertThat(row.get(r.columns().indexOf("first_name"))).isEqualTo("M***");
        assertThat(((Number) row.get(r.columns().indexOf("customer_id"))).intValue()).isEqualTo(1);
    }

    @Test
    void aliasAndFunctionDoNotBypassMasking() {
        QueryResult r = run("SELECT UPPER(email) AS customer_id, email AS note FROM customer WHERE customer_id = 1");

        assertThat(r.rows().getFirst()).containsExactly("MAR***", "M***@sakilacustomer.org");
    }

    // ───────── 도구 ─────────

    @Test
    void listTablesFindsSakila() {
        JsonNode r = json(stack().schemaTools().listTables(null, null));

        List<String> names = new ArrayList<>();
        r.get("tables").forEach(t -> names.add(t.get("name").asString()));
        assertThat(names).contains("actor", "film", "payment", "customer_list");
        assertThat(r.get("rowCounts").asString()).isEqualTo("통계 추정치");
    }

    @Test
    void describeTableShowsForeignKeysAndMasking() {
        JsonNode r = json(stack().schemaTools().describeTable("customer", null));

        List<String> refs = new ArrayList<>();
        r.get("references").forEach(f -> refs.add(f.get("toTable").asString().replaceFirst("^[^.]+\\.", "")));
        assertThat(refs).contains("address", "store");
        r.get("columns").forEach(c -> {
            if (c.get("name").asString().equals("email")) assertThat(c.get("masked").asString()).isEqualTo("이메일");
        });
    }

    @Test
    void joinPathRunsOnRealDatabase() {
        JsonNode r = json(stack().schemaTools().findJoinPath("film", "payment", null));
        JsonNode path = r.get("paths").get(0);
        assertThat(path.get("tables")).hasSize(4);

        Object count = single("SELECT COUNT(*) " + path.get("sql").asString());

        assertThat(((Number) count).longValue()).isPositive();
    }

    @Test
    void profileColumn() {
        JsonNode r = json(stack().dataTools().profileColumn("film", "rating", null));

        assertThat(r.get("distinctCount").asLong()).isEqualTo(5);
        assertThat(r.get("topValues").get(0).get("value").asString()).isEqualTo("PG-13");
        assertThat(r.get("topValues").get(0).get("count").asLong()).isEqualTo(223);
    }

    @Test
    void sampleRowsHidesPasswords() {
        JsonNode r = json(stack().dataTools().sampleRows("staff", null, null));

        List<String> columns = new ArrayList<>();
        r.get("columns").forEach(c -> columns.add(c.asString()));
        int pw = columns.indexOf("password");
        r.get("rows").forEach(row -> assertThat(row.get(pw).asString()).isEqualTo("****"));
    }

    @Test
    void explainShowsFullScan() {
        JsonNode r = json(stack().queryTools().explainQuery("SELECT * FROM film WHERE description LIKE '%Drama%'", null));

        assertThat(r.get("plan")).isNotEmpty();
        assertThat(r.get("fullScanTables")).isNotNull();
        assertThat(r.get("fullScanTables").get(0).asString()).contains("film");
    }
}
