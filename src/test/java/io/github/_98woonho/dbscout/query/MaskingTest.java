package io.github._98woonho.dbscout.query;

import static io.github._98woonho.dbscout.query.SakilaFixture.column;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github._98woonho.dbscout.mask.Masker;

/**
 * 개인정보 가리기를 실제 Sakila DB로 확인한다. 별칭·함수·서브쿼리·WITH·UNION·별표로 우회해도 가려져야 한다.
 * 데모 DB는 기본 규칙(email, phone, password, address 등) + 고객·직원 이름을 가린다.
 */
class MaskingTest {

    @TempDir
    static Path dir;
    static QueryExecutor executor;

    @BeforeAll
    static void setUp() {
        executor = SakilaFixture.executor(dir);
    }

    private static QueryResult run(String sql) {
        return executor.run(null, sql, 20, null);
    }

    /** 이메일 형식으로 가려짐: M***@sakilacustomer.org */
    private static final String EMAIL_MASKED = "^[A-Z]\\*\\*\\*@sakila(customer|staff)\\.(org|com)$";
    /** 앞 1~3자만 남고 가려짐 */
    private static final String PARTIAL_MASKED = "^.{1,3}\\*\\*\\*$";

    static Stream<Arguments> bypassAttempts() {
        return Stream.of(
                Arguments.argumentSet("그대로", "SELECT email AS v FROM customer", EMAIL_MASKED),
                Arguments.argumentSet("다른 별칭", "SELECT email AS contact FROM customer", EMAIL_MASKED),
                Arguments.argumentSet("키처럼 보이는 별칭", "SELECT email AS customer_id FROM customer", EMAIL_MASKED),
                Arguments.argumentSet("테이블 이름 붙이기", "SELECT customer.email AS v FROM customer", EMAIL_MASKED),
                Arguments.argumentSet("함수로 감싸기", "SELECT UPPER(email) AS v FROM customer", PARTIAL_MASKED),
                Arguments.argumentSet("이어 붙이기", "SELECT first_name || ' ' || email AS v FROM customer", PARTIAL_MASKED),
                Arguments.argumentSet("집계 MAX", "SELECT MAX(email) AS v FROM customer", PARTIAL_MASKED),
                Arguments.argumentSet("집계 group_concat", "SELECT group_concat(email) AS v FROM customer", PARTIAL_MASKED),
                Arguments.argumentSet("FROM 서브쿼리", "SELECT x AS v FROM (SELECT email AS x FROM customer) t", EMAIL_MASKED),
                Arguments.argumentSet("서브쿼리 별표", "SELECT t.email AS v FROM (SELECT * FROM customer) t", EMAIL_MASKED),
                Arguments.argumentSet("WITH", "WITH e AS (SELECT email AS x FROM customer) SELECT x AS v FROM e", EMAIL_MASKED),
                Arguments.argumentSet("WITH 컬럼 목록", "WITH e(x) AS (SELECT email FROM customer) SELECT x AS v FROM e", EMAIL_MASKED),
                Arguments.argumentSet("UNION 뒤쪽", "SELECT 'none' AS v UNION ALL SELECT email FROM customer", null),
                Arguments.argumentSet("스칼라 서브쿼리",
                        "SELECT (SELECT email FROM customer WHERE customer_id = a.actor_id) AS v FROM actor a", PARTIAL_MASKED),
                Arguments.argumentSet("상관 서브쿼리", "SELECT (SELECT c.email) AS v FROM customer c", PARTIAL_MASKED),
                Arguments.argumentSet("CASE 안에서 쓰기",
                        "SELECT CASE WHEN customer_id > 0 THEN email END AS v FROM customer", PARTIAL_MASKED),
                Arguments.argumentSet("CASE 조건과 값 모두",
                        "SELECT CASE WHEN email LIKE '%.org' THEN email END AS v FROM customer", PARTIAL_MASKED),
                Arguments.argumentSet("CASE ELSE",
                        "SELECT CASE WHEN customer_id < 0 THEN 'x' ELSE email END AS v FROM customer", PARTIAL_MASKED),
                Arguments.argumentSet("조건과 값을 함께 함수에",
                        "SELECT COALESCE(NULLIF(email = 'x', 0), email) AS v FROM customer", PARTIAL_MASKED),
                Arguments.argumentSet("테이블 함수 json_each",
                        "SELECT value AS v FROM json_each((SELECT json_group_array(email) FROM customer))", PARTIAL_MASKED));
    }

    @ParameterizedTest
    @MethodSource("bypassAttempts")
    void emailStaysMasked(String sql, String pattern) {
        QueryResult r = run(sql);

        String name = r.columns().getFirst(); // 별칭을 바꾼 사례도 있으므로 첫 컬럼을 본다
        List<Object> values = column(r, name);
        assertThat(values).isNotEmpty();
        assertThat(values).noneMatch(v -> v != null && v.toString().toUpperCase().contains("SMITH@"));
        assertThat(values).noneMatch(v -> v != null && v.toString().matches("(?i).*[a-z]+\\.[a-z]+@sakila.*"));
        if (pattern != null) {
            assertThat(values).allSatisfy(v -> assertThat(v.toString()).matches(pattern));
        }
        assertThat(r.maskedColumns()).extracting(QueryResult.MaskedColumn::column).contains(name);
    }

    @Test
    void selectStarMasksOnlySensitiveColumns() {
        QueryResult r = run("SELECT * FROM customer WHERE customer_id = 1");

        assertThat(column(r, "customer_id")).containsExactly(1);
        assertThat(column(r, "email")).containsExactly("M***@sakilacustomer.org");
        assertThat(column(r, "first_name")).containsExactly("M***"); // 데모 규칙: 고객 이름
        assertThat(column(r, "last_name")).containsExactly("S***");
        assertThat(column(r, "address_id")).containsExactly(5);
        assertThat(r.maskedColumns()).extracting(QueryResult.MaskedColumn::column)
                .containsExactlyInAnyOrder("first_name", "last_name", "email");
    }

    @Test
    void tableStarIsExpandedToo() {
        QueryResult r = run("SELECT c.* FROM customer c JOIN address a ON c.address_id = a.address_id WHERE c.customer_id = 1");

        assertThat(column(r, "email")).containsExactly("M***@sakilacustomer.org");
    }

    @Test
    void sameColumnNameIsJudgedByItsTable() {
        QueryResult r = run("""
                SELECT a.first_name AS actor_name, c.first_name AS customer_name
                FROM actor a JOIN customer c ON a.actor_id = c.customer_id
                WHERE a.actor_id = 1""");

        assertThat(column(r, "actor_name")).containsExactly("PENELOPE"); // 배우 이름은 가리지 않음
        assertThat(column(r, "customer_name")).containsExactly("M***");
    }

    @Test
    void unionTakesTheStrongerSide() {
        QueryResult r = run("SELECT first_name AS v FROM actor UNION SELECT first_name FROM customer");

        assertThat(column(r, "v")).allSatisfy(v -> assertThat(v.toString()).endsWith("***"));
    }

    @Test
    void secretsAreHiddenCompletely() {
        QueryResult r = run("SELECT staff_id, username, password FROM staff ORDER BY staff_id");

        assertThat(column(r, "password")).containsOnly(Masker.HIDDEN);
        assertThat(column(r, "username")).containsExactly("M***", "J***");
    }

    @Test
    void viewColumnsAreMaskedByRules() {
        QueryResult r = run("SELECT ID, name, zip_code, city FROM customer_list WHERE ID = 1");

        assertThat(column(r, "name")).containsExactly("MA***"); // 데모 규칙: customer_list.name ("MARY SMITH")
        assertThat(column(r, "zip_code")).containsExactly("3***");
        assertThat(column(r, "city")).containsExactly("Sasebo");
    }

    @Test
    void phoneKeepsOnlyLastFourDigits() {
        // 이 Sakila판의 address.phone 은 모두 공백이라 값을 직접 만들어 확인한다. 결과 이름이 phone 이면 가린다
        QueryResult r = run("SELECT '010-1234-5678' AS phone");

        assertThat(column(r, "phone")).containsExactly("***-****-5678");
    }

    @Test
    void castToNumberIsStillMasked() {
        QueryResult r = run("SELECT CAST(p AS INTEGER) AS n FROM (SELECT phone AS p FROM address) t LIMIT 3");

        assertThat(column(r, "n")).hasSize(3).allSatisfy(v -> assertThat(v).isEqualTo(Masker.HIDDEN));
        assertThat(r.maskedColumns()).extracting(QueryResult.MaskedColumn::column).containsExactly("n");
    }

    @Test
    void countsAndFiltersAreNotMasked() {
        // 개수만 돌려주는 COUNT와 WHERE 조건은 개인정보 값을 내보내지 않는다(설계 결정)
        QueryResult r = run("SELECT COUNT(email) AS n, COUNT(DISTINCT email) AS d FROM customer WHERE email LIKE '%.org'");

        assertThat(column(r, "n")).containsExactly(599);
        assertThat(column(r, "d")).containsExactly(599);
        assertThat(r.maskedColumns()).isEmpty();
    }

    static Stream<Arguments> conditionsOnly() {
        // 참/거짓만 내는 조건은 WHERE와 같게 본다(설계 결정). 결과는 참·거짓이나 그 개수뿐이라 원래 값이 나가지 않는다.
        // 기대값은 같은 조건을 WHERE에 넣고 센 값이다
        return Stream.of(
                Arguments.argumentSet("IS NOT NULL 합계", "SELECT SUM(email IS NOT NULL) AS v FROM customer",
                        "SELECT COUNT(*) AS v FROM customer WHERE email IS NOT NULL"),
                Arguments.argumentSet("CASE 안의 LIKE 개수",
                        "SELECT COUNT(CASE WHEN email LIKE '%.org' THEN 1 END) AS v FROM customer",
                        "SELECT COUNT(*) AS v FROM customer WHERE email LIKE '%.org'"),
                Arguments.argumentSet("CASE 안의 LIKE 합계",
                        "SELECT SUM(CASE WHEN email LIKE 'M%' THEN 1 ELSE 0 END) AS v FROM customer",
                        "SELECT COUNT(*) AS v FROM customer WHERE email LIKE 'M%'"),
                Arguments.argumentSet("같음 비교",
                        "SELECT SUM(email = 'MARY.SMITH@sakilacustomer.org') AS v FROM customer",
                        "SELECT COUNT(*) AS v FROM customer WHERE email = 'MARY.SMITH@sakilacustomer.org'"),
                Arguments.argumentSet("IN",
                        "SELECT SUM(email IN ('MARY.SMITH@sakilacustomer.org', 'x')) AS v FROM customer",
                        "SELECT COUNT(*) AS v FROM customer WHERE email IN ('MARY.SMITH@sakilacustomer.org', 'x')"),
                Arguments.argumentSet("IN 서브쿼리",
                        "SELECT SUM(address_id IN (SELECT address_id FROM customer WHERE email LIKE 'M%')) AS v FROM address",
                        "SELECT COUNT(*) AS v FROM address WHERE address_id IN (SELECT address_id FROM customer WHERE email LIKE 'M%')"),
                Arguments.argumentSet("BETWEEN",
                        "SELECT SUM(email BETWEEN 'A' AND 'B') AS v FROM customer",
                        "SELECT COUNT(*) AS v FROM customer WHERE email BETWEEN 'A' AND 'B'"),
                Arguments.argumentSet("EXISTS",
                        "SELECT SUM(EXISTS (SELECT email FROM customer c WHERE c.address_id = a.address_id)) AS v FROM address a",
                        "SELECT COUNT(*) AS v FROM address a WHERE EXISTS (SELECT 1 FROM customer c WHERE c.address_id = a.address_id)"));
    }

    @ParameterizedTest
    @MethodSource("conditionsOnly")
    void conditionsAreNotMasked(String sql, String whereSql) {
        QueryResult expected = run(whereSql);
        QueryResult r = run(sql);

        assertThat(column(expected, "v").getFirst()).isInstanceOf(Integer.class).isNotEqualTo(0);
        assertThat(column(r, "v")).containsExactlyElementsOf(column(expected, "v"));
        assertThat(r.maskedColumns()).isEmpty();
    }

    @Test
    void caseLabelsFromConditionsAreNotMasked() {
        QueryResult r = run("""
                SELECT customer_id,
                       CASE WHEN email LIKE '%.org' THEN 'org' ELSE 'other' END AS domain,
                       CASE email WHEN 'nobody@example.com' THEN 'yes' ELSE 'no' END AS known
                FROM customer WHERE customer_id = 1""");

        assertThat(column(r, "domain")).containsExactly("org");
        assertThat(column(r, "known")).containsExactly("no");
        assertThat(r.maskedColumns()).isEmpty();
    }

    @Test
    void countAliasEndingInEmailIsNotMasked() {
        // 별칭이 email로 끝나도, 개인정보를 조건·COUNT에만 쓴 개수는 별칭 규칙으로 가리지 않는다
        QueryResult r = run("""
                SELECT SUM(email IS NOT NULL) AS has_email,
                       COUNT(email) AS with_email,
                       SUM(CASE WHEN email LIKE '%.org' THEN 1 ELSE 0 END) AS org_email
                FROM customer""");

        assertThat(column(r, "has_email")).containsExactly(599);
        assertThat(column(r, "with_email")).containsExactly(599);
        assertThat(column(r, "org_email")).containsExactly(599);
        assertThat(r.maskedColumns()).isEmpty();
    }

    @Test
    void aliasRuleStillAppliesWhenAValueColumnIsUsed() {
        // 값 자리에 다른 컬럼이 나오면 무엇이 담겼는지 이름 말고는 알 수 없으므로 별칭 규칙을 그대로 쓴다
        QueryResult r = run("""
                SELECT CASE WHEN email IS NOT NULL THEN last_update END AS with_email,
                       1 AS email
                FROM customer WHERE customer_id = 1""");

        assertThat(r.maskedColumns()).extracting(QueryResult.MaskedColumn::column)
                .containsExactlyInAnyOrder("with_email", "email");
    }

    @Test
    void windowOrderingDoesNotTaintTheNumber() {
        QueryResult r = run("SELECT customer_id, ROW_NUMBER() OVER (ORDER BY email) AS rn FROM customer ORDER BY rn LIMIT 3");

        assertThat(column(r, "rn")).containsExactly(1, 2, 3);
    }

    @Test
    void keysAndNonPersonalColumnsAreUntouched() {
        QueryResult r = run("SELECT address_id, district, city_id FROM address WHERE address_id = 5");

        assertThat(column(r, "address_id")).containsExactly(5);
        assertThat(column(r, "city_id").getFirst()).isInstanceOf(Integer.class);
        assertThat(r.maskedColumns()).isEmpty();
    }
}
