package io.github._98woonho.dbscout.tools;

import static io.github._98woonho.dbscout.tools.ToolsFixture.error;
import static io.github._98woonho.dbscout.tools.ToolsFixture.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;

/** 도구 9개를 Sakila 데모 DB로 호출해 본다. */
class ToolsTest {

    @TempDir
    static Path dir;
    static ToolsFixture.Tools tools;

    @BeforeAll
    static void setUp() {
        tools = ToolsFixture.demo(dir);
    }

    private static List<String> names(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asString()));
        return out;
    }

    // ───────── list_connections ─────────

    @Test
    void listConnections() {
        JsonNode r = json(tools.connection().listConnections());

        assertThat(r.get("connections").get(0).get("name").asString()).isEqualTo("sakila");
        assertThat(r.get("limits").get("maxRows").asInt()).isEqualTo(1000);
        assertThat(r.toString()).doesNotContain("jdbc:");
    }

    // ───────── list_tables ─────────

    @Test
    void listTablesWithExactRowCounts() {
        JsonNode r = json(tools.schema().listTables(null, null));

        assertThat(r.get("tableCount").asInt()).isEqualTo(21); // 테이블 16 + 뷰 5 (SQLite판은 actor_info 뷰가 주석 처리됨)
        assertThat(r.get("rowCounts").asString()).isEqualTo("정확한 값");
        JsonNode actor = null;
        for (JsonNode t : r.get("tables")) if (t.get("name").asString().equals("actor")) actor = t;
        assertThat(actor).isNotNull();
        assertThat(actor.get("rows").asLong()).isEqualTo(200);
        assertThat(actor.get("type").asString()).isEqualTo("TABLE");
        assertThat(names(r.get("tables"), "name")).contains("customer_list").doesNotContain("sqlite_sequence");
    }

    @Test
    void listTablesFiltered() {
        JsonNode r = json(tools.schema().listTables("FILM", null));

        assertThat(names(r.get("tables"), "name")).containsExactlyInAnyOrder(
                "film", "film_actor", "film_category", "film_list", "film_text", "sales_by_film_category");
    }

    // ───────── describe_table ─────────

    @Test
    void describeTable() {
        JsonNode r = json(tools.schema().describeTable("CUSTOMER", null)); // 대소문자 무시

        assertThat(r.get("table").asString()).isEqualTo("customer");
        assertThat(r.get("rows").asLong()).isEqualTo(599);
        assertThat(names(r.get("columns"), "name")).containsExactly("customer_id", "store_id", "first_name",
                "last_name", "email", "address_id", "active", "create_date", "last_update");
        assertThat(r.get("primaryKey").get(0).asString()).isEqualTo("customer_id");
        JsonNode email = r.get("columns").get(4);
        assertThat(email.get("masked").asString()).isEqualTo("이메일");
        assertThat(names(r.get("references"), "toTable")).containsExactlyInAnyOrder("address", "store");
        assertThat(names(r.get("referencedBy"), "fromTable")).containsExactlyInAnyOrder("payment", "rental");
        assertThat(names(r.get("indexes"), "name")).isNotEmpty();
        assertThat(r.get("note").asString()).contains("가려집니다");
    }

    @Test
    void describeUnknownTableSuggestsNames() {
        String msg = error(tools.schema().describeTable("custmer", null));

        assertThat(msg).contains("비슷한 이름").contains("customer");
    }

    // ───────── search_schema ─────────

    @Test
    void searchSchemaFindsColumns() {
        JsonNode r = json(tools.schema().searchSchema("amount mail", null));

        List<String> found = new ArrayList<>();
        r.get("columns").forEach(c -> found.add(c.get("table").asString() + "." + c.get("column").asString()));
        assertThat(found).contains("payment.amount", "customer.email", "staff.email");
    }

    @Test
    void searchSchemaNothingFound() {
        JsonNode r = json(tools.schema().searchSchema("결제", null));

        assertThat(r.get("note").asString()).contains("영어 이름");
    }

    // ───────── find_join_path ─────────

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    @Test
    void joinPathFilmToPayment() {
        JsonNode r = json(tools.schema().findJoinPath("film", "payment", null));

        JsonNode path = r.get("paths").get(0);
        assertThat(strings(path.get("tables"))).containsExactly("film", "inventory", "rental", "payment");
        assertThat(path.get("sql").asString()).isEqualTo("""
                FROM film
                JOIN inventory ON inventory.film_id = film.film_id
                JOIN rental ON rental.inventory_id = inventory.inventory_id
                JOIN payment ON payment.rental_id = rental.rental_id""");
    }

    @Test
    void joinPathSqlActuallyRuns() {
        JsonNode r = json(tools.schema().findJoinPath("film", "payment", null));
        String sql = "SELECT COUNT(*) AS n " + r.get("paths").get(0).get("sql").asString();

        JsonNode q = json(tools.query().runQuery(sql, null, null, null));

        assertThat(q.get("rows").get(0).get(0).asLong()).isPositive();
    }

    @Test
    void joinPathShowsAlternativesOfSameLength() {
        // film → language 는 language_id(원어)와 original_language_id(원작 언어) 두 갈래
        JsonNode r = json(tools.schema().findJoinPath("film", "language", null));

        assertThat(r.get("paths")).hasSize(2);
        List<String> conditions = new ArrayList<>();
        r.get("paths").forEach(p -> conditions.addAll(strings(p.get("conditions"))));
        assertThat(conditions).containsExactlyInAnyOrder(
                "language.language_id = film.language_id", "language.language_id = film.original_language_id");
        assertThat(r.get("note").asString()).contains("2개");
    }

    @Test
    void noForeignKeyPathSuggestsSameNameColumns() {
        JsonNode r = json(tools.schema().findJoinPath("film", "film_list", null)); // 뷰라 외래키 없음

        assertThat(r.has("paths")).isFalse();
        assertThat(strings(r.get("sameNameColumns"))).contains("title", "rating").doesNotContain("description");
        assertThat(r.get("note").asString()).contains("외래키로");
    }

    // ───────── sample_rows ─────────

    @Test
    void sampleRowsMasksPersonalData() {
        JsonNode r = json(tools.data().sampleRows("customer", 3, null));

        assertThat(r.get("rowCount").asInt()).isEqualTo(3);
        int email = strings(r.get("columns")).indexOf("email");
        r.get("rows").forEach(row -> assertThat(row.get(email).asString()).matches("^.\\*\\*\\*@sakilacustomer\\.org$"));
    }

    @Test
    void sampleRowsIsCapped() {
        JsonNode r = json(tools.data().sampleRows("rental", 500, null));

        assertThat(r.get("rowCount").asInt()).isEqualTo(DataTools.MAX_SAMPLE);
    }

    // ───────── profile_column ─────────

    @Test
    void profileColumn() {
        JsonNode r = json(tools.data().profileColumn("film", "rating", null));

        assertThat(r.get("totalRows").asLong()).isEqualTo(1000);
        assertThat(r.get("nullCount").asLong()).isZero();
        assertThat(r.get("distinctCount").asLong()).isEqualTo(5);
        JsonNode top = r.get("topValues").get(0);
        assertThat(top.get("value").asString()).isEqualTo("PG-13");
        assertThat(top.get("count").asLong()).isEqualTo(223);
    }

    @Test
    void profileOfPersonalColumnIsMasked() {
        JsonNode r = json(tools.data().profileColumn("customer", "email", null));

        assertThat(r.get("masked").asString()).isEqualTo("이메일");
        assertThat(r.get("min").asString()).endsWith("***");
        r.get("topValues").forEach(v -> assertThat(v.get("value").asString()).contains("***@"));
        assertThat(r.get("note").asString()).contains("가려서");
    }

    @Test
    void profileUnknownColumnListsColumns() {
        String msg = error(tools.data().profileColumn("film", "ratting", null));

        assertThat(msg).contains("rating");
    }

    // ───────── explain_query ─────────

    @Test
    void explainShowsFullScan() {
        JsonNode r = json(tools.query().explainQuery("SELECT * FROM film WHERE title = 'ACADEMY DINOSAUR'", null));

        assertThat(strings(r.get("fullScanTables"))).containsExactly("film");
        assertThat(strings(r.get("plan"))).anyMatch(l -> l.startsWith("SCAN film"));
    }

    @Test
    void explainShowsIndexUse() {
        JsonNode r = json(tools.query().explainQuery("SELECT * FROM film WHERE film_id = 1", null));

        assertThat(r.has("fullScanTables")).isFalse();
        assertThat(strings(r.get("plan"))).anyMatch(l -> l.contains("SEARCH film"));
    }

    @Test
    void explainRunsTheSameGuard() {
        assertThat(error(tools.query().explainQuery("DELETE FROM film", null))).contains("SELECT");
    }

    // ───────── run_query ─────────

    @Test
    void runQuery() {
        JsonNode r = json(tools.query().runQuery(
                "SELECT c.name, COUNT(*) AS films FROM category c JOIN film_category fc ON fc.category_id = c.category_id "
                        + "GROUP BY c.name ORDER BY films DESC, c.name LIMIT 3", null, null, null));

        assertThat(strings(r.get("columns"))).containsExactly("name", "films");
        assertThat(r.get("rows").get(0).get(0).asString()).isEqualTo("Sports");
        assertThat(r.get("rows").get(0).get(1).asInt()).isEqualTo(74);
        assertThat(r.get("truncated").asBoolean()).isFalse();
    }

    @Test
    void runQueryErrorsAreToolErrors() {
        assertThat(error(tools.query().runQuery("DROP TABLE film", null, null, null))).startsWith("[SELECT가 아닌 문장]");
        assertThat(error(tools.query().runQuery("SELECT * FROM nope", null, null, null))).startsWith("[DB 오류]");
        assertThat(error(tools.query().runQuery("SELECT 1", null, null, "other"))).contains("사용 가능한 접속: sakila");
    }

    // ───────── 숨긴 테이블 ─────────

    @Nested
    class HiddenTables {

        @TempDir
        Path hiddenDir;

        @Test
        void hiddenTableIsInvisibleAndBlocked() {
            var t = ToolsFixture.withHidden(hiddenDir, List.of("staff", "main.payment"));

            JsonNode list = json(t.schema().listTables(null, null));
            assertThat(names(list.get("tables"), "name")).doesNotContain("staff", "payment").contains("customer");
            assertThat(error(t.schema().describeTable("staff", null))).contains("없습니다");
            assertThat(error(t.query().runQuery("SELECT * FROM staff", null, null, null))).contains("숨긴 테이블");
            assertThat(error(t.query().runQuery("SELECT 1 FROM rental r JOIN (SELECT * FROM staff) s ON 1 = 1",
                    null, null, null))).contains("숨긴 테이블");
            // 외래키 목록에서도 빠진다
            JsonNode customer = json(t.schema().describeTable("customer", null));
            assertThat(names(customer.get("referencedBy"), "fromTable")).doesNotContain("payment");
        }
    }
}
