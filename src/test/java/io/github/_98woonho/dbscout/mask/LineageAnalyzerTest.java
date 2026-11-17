package io.github._98woonho.dbscout.mask;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github._98woonho.dbscout.connection.DbKind;
import io.github._98woonho.dbscout.guard.SqlGuard;
import io.github._98woonho.dbscout.mask.LineageAnalyzer.MaskPlan;
import io.github._98woonho.dbscout.mask.LineageAnalyzer.OutputColumn;

/** 실제 DB 없이 분석기만 확인한다(SQLite로 실행할 수 없는 PostgreSQL 문법 포함). */
class LineageAnalyzerTest {

    private static final Map<String, List<String>> TABLES = Map.of(
            "customer", List.of("customer_id", "first_name", "email"),
            "film", List.of("film_id", "title"));

    private static final SchemaLookup LOOKUP = (schema, table) -> TABLES.getOrDefault(table.toLowerCase(), List.of());

    private static MaskPlan plan(String sql) {
        var select = new SqlGuard().check(sql, DbKind.POSTGRESQL).select();
        return new LineageAnalyzer(MaskPolicy.defaults(), LOOKUP).plan(select);
    }

    private static MaskKind kindOf(MaskPlan plan, int index) {
        return plan.outputs().get(index).kind();
    }

    @Test
    void wholeRowReferenceIsMasked() {
        // PostgreSQL: SELECT c FROM customer c → (1,MARY,mary@...) 처럼 행 전체가 한 값으로 나온다
        MaskPlan p = plan("SELECT c FROM customer c");

        assertThat(kindOf(p, 0)).isEqualTo(MaskKind.EMAIL);
        assertThat(p.outputs().getFirst().derived()).isTrue();
    }

    @Test
    void rowToJsonIsMasked() {
        assertThat(kindOf(plan("SELECT row_to_json(c) FROM customer c"), 0)).isEqualTo(MaskKind.EMAIL);
        assertThat(kindOf(plan("SELECT row_to_json(c.*) FROM customer c"), 0)).isEqualTo(MaskKind.EMAIL);
        assertThat(kindOf(plan("SELECT json_agg(customer) FROM customer"), 0)).isEqualTo(MaskKind.EMAIL);
    }

    @Test
    void wholeRowOfSafeTableIsNotMasked() {
        assertThat(kindOf(plan("SELECT f FROM film f"), 0)).isNull();
    }

    @Test
    void starExpandsInTableOrder() {
        MaskPlan p = plan("SELECT * FROM film f JOIN customer c ON f.film_id = c.customer_id");

        assertThat(p.outputs()).extracting(OutputColumn::name)
                .containsExactly("film_id", "title", "customer_id", "first_name", "email");
        assertThat(p.outputs()).extracting(OutputColumn::kind)
                .containsExactly(null, null, null, null, MaskKind.EMAIL);
    }

    @Test
    void lateralSubqueryIsFollowed() {
        MaskPlan p = plan("SELECT l.x FROM film f, LATERAL (SELECT email AS x FROM customer c WHERE c.customer_id = f.film_id) l");

        assertThat(kindOf(p, 0)).isEqualTo(MaskKind.EMAIL);
    }

    @Test
    void unknownTableStarFallsBackToMaskingEverythingIfPersonalDataIsTouched() {
        // mystery 테이블의 컬럼을 몰라 결과 위치를 확정할 수 없다. email을 내보내므로 모든 컬럼을 가린다
        MaskPlan p = plan("SELECT c.email AS foo, m.* FROM customer c JOIN mystery m ON c.customer_id = m.id");

        assertThat(p.outputs()).isNull();
        assertThat(p.touched()).isEqualTo(MaskKind.EMAIL);
    }

    @Test
    void unknownTableStarWithoutPersonalDataIsLeftToNameRules() {
        MaskPlan p = plan("SELECT * FROM mystery");

        assertThat(p.outputs()).isNull();
        assertThat(p.touched()).isNull(); // 결과 컬럼 이름 규칙(email 등)으로만 판단
    }

    @Test
    void recursiveCteKeepsTaint() {
        MaskPlan p = plan("""
                WITH RECURSIVE r(n, e) AS (
                  SELECT 1, email FROM customer
                  UNION ALL
                  SELECT n + 1, e FROM r WHERE n < 3)
                SELECT e FROM r""");

        assertThat(kindOf(p, 0)).isEqualTo(MaskKind.EMAIL);
    }
}
