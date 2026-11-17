package io.github._98woonho.dbscout.guard;

import static io.github._98woonho.dbscout.connection.DbKind.MYSQL;
import static io.github._98woonho.dbscout.connection.DbKind.POSTGRESQL;
import static io.github._98woonho.dbscout.connection.DbKind.SQLITE;
import static io.github._98woonho.dbscout.guard.RejectReason.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github._98woonho.dbscout.connection.DbKind;

/**
 * SQL 검사기 사례표. 각 사례를 해당하는 DB 종류마다 검사한다.
 * <ul>
 *   <li>{@link #ALLOWED}: 통과해야 하는 SELECT (분석 도구가 실제로 쓸 만한 쿼리)</li>
 *   <li>{@link #BLOCKED}: 막아야 하는 SQL과 막는 이유</li>
 * </ul>
 */
public class SqlGuardTest {

    private static final Set<DbKind> ALL = EnumSet.allOf(DbKind.class);
    private static final Set<DbKind> MY = EnumSet.of(MYSQL);
    private static final Set<DbKind> PG = EnumSet.of(POSTGRESQL);
    private static final Set<DbKind> LITE = EnumSet.of(SQLITE);
    private static final Set<DbKind> MY_PG = EnumSet.of(MYSQL, POSTGRESQL);
    private static final Set<DbKind> MY_LITE = EnumSet.of(MYSQL, SQLITE);
    private static final Set<DbKind> PG_LITE = EnumSet.of(POSTGRESQL, SQLITE);

    public record Allowed(String title, Set<DbKind> kinds, String sql) {
    }

    record Blocked(String title, Set<DbKind> kinds, RejectReason reason, String sql) {
    }

    public static final Allowed[] ALLOWED = {
            new Allowed("기본 조회", ALL, "SELECT * FROM actor"),
            new Allowed("조건·정렬·LIMIT", ALL,
                    "SELECT first_name, last_name FROM actor WHERE actor_id < 10 ORDER BY last_name LIMIT 5"),
            new Allowed("LIMIT OFFSET", ALL, "SELECT title FROM film ORDER BY title LIMIT 10 OFFSET 20"),
            new Allowed("여러 테이블 JOIN과 집계", ALL, """
                    SELECT c.name, SUM(p.amount) AS total
                    FROM payment p
                    JOIN rental r ON p.rental_id = r.rental_id
                    JOIN inventory i ON r.inventory_id = i.inventory_id
                    JOIN film_category fc ON i.film_id = fc.film_id
                    JOIN category c ON fc.category_id = c.category_id
                    GROUP BY c.name HAVING SUM(p.amount) > 100
                    ORDER BY total DESC"""),
            new Allowed("LEFT JOIN", ALL,
                    "SELECT f.title FROM film f LEFT JOIN inventory i ON f.film_id = i.film_id WHERE i.inventory_id IS NULL"),
            new Allowed("IN 서브쿼리", ALL,
                    "SELECT title FROM film WHERE film_id IN (SELECT film_id FROM inventory WHERE store_id = 1)"),
            new Allowed("EXISTS", ALL,
                    "SELECT c.customer_id FROM customer c WHERE NOT EXISTS (SELECT 1 FROM rental r WHERE r.customer_id = c.customer_id)"),
            new Allowed("FROM 서브쿼리", ALL,
                    "SELECT AVG(n) FROM (SELECT customer_id, COUNT(*) AS n FROM rental GROUP BY customer_id) t"),
            new Allowed("WITH", ALL, """
                    WITH top AS (SELECT customer_id, COUNT(*) AS n FROM rental GROUP BY customer_id)
                    SELECT * FROM top ORDER BY n DESC LIMIT 10"""),
            new Allowed("재귀 WITH", ALL,
                    "WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM n WHERE x < 10) SELECT x FROM n"),
            new Allowed("UNION", ALL, "SELECT first_name FROM actor UNION SELECT first_name FROM customer"),
            new Allowed("윈도 함수", ALL,
                    "SELECT customer_id, amount, RANK() OVER (PARTITION BY customer_id ORDER BY amount DESC) AS rk FROM payment"),
            new Allowed("CASE", ALL,
                    "SELECT title, CASE WHEN length > 120 THEN 'long' ELSE 'short' END AS kind FROM film"),
            new Allowed("COUNT DISTINCT", ALL, "SELECT COUNT(DISTINCT customer_id) FROM rental"),
            new Allowed("문자열 안의 주석·세미콜론 기호", ALL, "SELECT 'a -- b; /* c */ # d' AS s FROM actor"),
            new Allowed("작은따옴표 이스케이프", ALL, "SELECT 'it''s' AS s FROM actor"),
            new Allowed("위험 함수와 이름이 비슷한 컬럼", ALL, "SELECT 1 AS sleep_hours, 2 AS pg_sleep_count FROM actor"),
            new Allowed("주석이 섞인 쿼리", ALL,
                    "-- 고객별 대여 수\nSELECT customer_id, /* 건수 */ COUNT(*) AS n FROM rental GROUP BY customer_id;"),
            new Allowed("따옴표 안의 $ 와 //", ALL,
                    "SELECT title FROM film WHERE title LIKE '%$%' OR description LIKE '%//%'"),
            new Allowed("lower()는 lo_ 함수가 아님", ALL, "SELECT LOWER(email) FROM customer"),
            new Allowed("정보 스키마", MY_PG,
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"),
            new Allowed("SQLite 시스템 테이블", LITE, "SELECT name FROM sqlite_master WHERE type = 'table'"),
            new Allowed("큰따옴표 이름(한글 별칭)", PG_LITE, "SELECT COUNT(*) AS \"고객 수\" FROM customer"),
            new Allowed("백틱 이름", MY_LITE, "SELECT `first_name` FROM `actor`"),
            new Allowed("SQLite 대괄호 이름", LITE, "SELECT [first_name] FROM [actor]"),
            new Allowed("MySQL 날짜 함수", MY,
                    "SELECT DATE_FORMAT(payment_date, '%Y-%m') AS ym, SUM(amount) FROM payment GROUP BY ym"),
            new Allowed("PostgreSQL 캐스트·ILIKE·date_trunc", PG,
                    "SELECT date_trunc('month', payment_date)::date AS m, SUM(amount) FROM payment WHERE CAST(amount AS text) ILIKE '%9' GROUP BY 1"),
            new Allowed("SQLite 날짜 함수", LITE,
                    "SELECT strftime('%Y-%m', payment_date) AS ym, SUM(amount) FROM payment GROUP BY ym"),
    };

    static final Blocked[] BLOCKED = {
            // ── 데이터 변경 ──
            new Blocked("INSERT", ALL, NOT_SELECT, "INSERT INTO actor (first_name, last_name) VALUES ('A', 'B')"),
            new Blocked("UPDATE", ALL, NOT_SELECT, "UPDATE film SET rental_rate = rental_rate * 2"),
            new Blocked("DELETE", ALL, NOT_SELECT, "DELETE FROM payment"),
            new Blocked("MERGE", PG, NOT_SELECT,
                    "MERGE INTO actor a USING actor b ON a.actor_id = b.actor_id WHEN MATCHED THEN DELETE"),
            new Blocked("REPLACE", MY, NOT_SELECT, "REPLACE INTO actor (actor_id, first_name) VALUES (1, 'A')"),
            new Blocked("INSERT ... SELECT", ALL, NOT_SELECT, "INSERT INTO actor SELECT * FROM actor"),
            new Blocked("TRUNCATE", MY_PG, NOT_SELECT, "TRUNCATE TABLE payment"),
            // ── 구조 변경 ──
            new Blocked("CREATE TABLE", ALL, NOT_SELECT, "CREATE TABLE t (id INT)"),
            new Blocked("CREATE TABLE AS SELECT", ALL, NOT_SELECT, "CREATE TABLE t AS SELECT * FROM customer"),
            new Blocked("DROP TABLE", ALL, NOT_SELECT, "DROP TABLE actor"),
            new Blocked("ALTER TABLE", ALL, NOT_SELECT, "ALTER TABLE actor ADD COLUMN x INT"),
            new Blocked("CREATE INDEX", ALL, NOT_SELECT, "CREATE INDEX i ON actor (first_name)"),
            new Blocked("CREATE VIEW", ALL, NOT_SELECT, "CREATE VIEW v AS SELECT * FROM actor"),
            // ── 권한·세션·트랜잭션 ──
            new Blocked("GRANT", MY_PG, NOT_SELECT, "GRANT ALL ON actor TO someone"),
            new Blocked("SET으로 읽기 전용 끄기", PG, NOT_SELECT, "SET default_transaction_read_only = off"),
            new Blocked("SET SESSION", MY, NOT_SELECT, "SET SESSION TRANSACTION READ WRITE"),
            new Blocked("트랜잭션 COMMIT", ALL, NOT_SELECT, "COMMIT"),
            new Blocked("LOCK TABLE", PG, NOT_SELECT, "LOCK TABLE actor IN ACCESS EXCLUSIVE MODE"),
            new Blocked("프로시저 CALL", MY_PG, NOT_SELECT, "CALL refresh_stats()"),
            new Blocked("EXPLAIN은 explain_query 도구로", ALL, NOT_SELECT, "EXPLAIN SELECT * FROM actor"),
            new Blocked("EXPLAIN ANALYZE DELETE", PG, NOT_SELECT, "EXPLAIN ANALYZE DELETE FROM actor"),
            new Blocked("PRAGMA", LITE, NOT_SELECT, "PRAGMA writable_schema = 1"),
            new Blocked("VACUUM", PG_LITE, NOT_SELECT, "VACUUM"),
            new Blocked("다른 DB 파일 붙이기 ATTACH", LITE, NOT_SELECT, "ATTACH DATABASE '/tmp/x.db' AS x"),
            new Blocked("VACUUM INTO 로 파일 쓰기", LITE, NOT_SELECT, "VACUUM INTO '/tmp/copy.db'"),
            new Blocked("COPY 로 파일 쓰기", PG, NOT_SELECT, "COPY customer TO '/tmp/customer.csv'"),
            new Blocked("LOAD DATA", MY, NOT_SELECT, "LOAD DATA INFILE '/tmp/x.csv' INTO TABLE actor"),
            new Blocked("PREPARE", PG, NOT_SELECT, "PREPARE p AS DELETE FROM actor"),
            // ── 여러 문장 ──
            new Blocked("SELECT 뒤에 DROP", ALL, MULTIPLE_STATEMENTS, "SELECT 1; DROP TABLE actor"),
            new Blocked("SELECT 두 개", ALL, MULTIPLE_STATEMENTS, "SELECT 1; SELECT 2"),
            new Blocked("주석 뒤에 숨긴 문장", ALL, MULTIPLE_STATEMENTS, "SELECT 1 /* 설명 */; DELETE FROM actor"),
            new Blocked("세미콜론 여러 개 사이", ALL, MULTIPLE_STATEMENTS, "SELECT 1;; SELECT 2;"),
            // ── SELECT INTO ──
            new Blocked("SELECT INTO 새 테이블", PG, SELECT_INTO, "SELECT * INTO backup FROM actor"),
            new Blocked("INTO OUTFILE", MY, SELECT_INTO, "SELECT * FROM customer INTO OUTFILE '/tmp/customer.csv'"),
            // JSqlParser 5.4가 이 MySQL 문법을 몰라 분석 단계에서 막힌다(모르면 막는다)
            new Blocked("INTO 변수", MY, PARSE_ERROR, "SELECT COUNT(*) INTO @n FROM actor"),
            // ── 행 잠금 ──
            new Blocked("FOR UPDATE", ALL, LOCKING, "SELECT * FROM actor FOR UPDATE"),
            new Blocked("FOR SHARE", MY_PG, LOCKING, "SELECT * FROM actor FOR SHARE"),
            new Blocked("FOR NO KEY UPDATE", PG, LOCKING, "SELECT * FROM actor FOR NO KEY UPDATE"),
            new Blocked("FOR UPDATE SKIP LOCKED", MY_PG, LOCKING, "SELECT * FROM actor FOR UPDATE SKIP LOCKED"),
            new Blocked("LOCK IN SHARE MODE", MY, PARSE_ERROR, "SELECT * FROM actor LOCK IN SHARE MODE"), // 위와 같음
            new Blocked("서브쿼리 안의 FOR UPDATE", PG, LOCKING,
                    "SELECT * FROM (SELECT * FROM actor FOR UPDATE) t"),
            // ── WITH 안에 숨긴 쓰기 (PostgreSQL) ──
            new Blocked("WITH 안의 DELETE", PG, WRITE_IN_QUERY,
                    "WITH d AS (DELETE FROM payment RETURNING *) SELECT COUNT(*) FROM d"),
            new Blocked("WITH 안의 UPDATE", PG, WRITE_IN_QUERY,
                    "WITH u AS (UPDATE film SET rental_rate = 0 RETURNING film_id) SELECT * FROM u"),
            new Blocked("WITH 안의 INSERT", PG, WRITE_IN_QUERY,
                    "WITH i AS (INSERT INTO actor (first_name) VALUES ('x') RETURNING *) SELECT * FROM i"),
            // ── 금지 함수 ──
            new Blocked("pg_sleep", PG, FORBIDDEN_FUNCTION, "SELECT pg_sleep(10)"),
            new Blocked("WHERE 안의 pg_sleep", PG, FORBIDDEN_FUNCTION,
                    "SELECT * FROM actor WHERE actor_id = 1 AND pg_sleep(10) IS NOT NULL"),
            new Blocked("FROM 안의 pg_sleep", PG, FORBIDDEN_FUNCTION, "SELECT * FROM pg_sleep(10)"),
            new Blocked("스키마를 붙인 pg_sleep", PG, FORBIDDEN_FUNCTION, "SELECT pg_catalog.pg_sleep(1)"),
            new Blocked("따옴표로 감싼 pg_sleep", PG, FORBIDDEN_FUNCTION, "SELECT \"pg_sleep\"(1)"),
            new Blocked("대문자 PG_SLEEP", PG, FORBIDDEN_FUNCTION, "SELECT PG_SLEEP(1)"),
            new Blocked("깊은 서브쿼리 안의 pg_sleep", PG, FORBIDDEN_FUNCTION,
                    "SELECT * FROM actor WHERE actor_id IN (SELECT 1 FROM (SELECT pg_sleep(5) AS z) s)"),
            new Blocked("CASE 안의 pg_sleep", PG, FORBIDDEN_FUNCTION,
                    "SELECT CASE WHEN actor_id = 1 THEN pg_sleep(5) END FROM actor"),
            new Blocked("JOIN 조건 안의 sleep", MY, FORBIDDEN_FUNCTION,
                    "SELECT * FROM actor a JOIN film_actor fa ON a.actor_id = fa.actor_id AND SLEEP(1) = 0"),
            new Blocked("ORDER BY 안의 sleep", MY, FORBIDDEN_FUNCTION, "SELECT * FROM actor ORDER BY SLEEP(1)"),
            new Blocked("WITH 안의 pg_sleep", PG, FORBIDDEN_FUNCTION,
                    "WITH s AS (SELECT pg_sleep(5)) SELECT * FROM s"),
            new Blocked("UNION 뒤의 sleep", MY, FORBIDDEN_FUNCTION, "SELECT 1 UNION SELECT SLEEP(5)"),
            new Blocked("BENCHMARK", MY, FORBIDDEN_FUNCTION, "SELECT BENCHMARK(100000000, MD5('a'))"),
            new Blocked("pg_read_file", PG, FORBIDDEN_FUNCTION, "SELECT pg_read_file('/etc/passwd')"),
            new Blocked("pg_ls_dir", PG, FORBIDDEN_FUNCTION, "SELECT * FROM pg_ls_dir('.')"),
            new Blocked("lo_import", PG, FORBIDDEN_FUNCTION, "SELECT lo_import('/etc/passwd')"),
            new Blocked("LOAD_FILE", MY, FORBIDDEN_FUNCTION, "SELECT LOAD_FILE('/etc/passwd')"),
            new Blocked("load_extension", LITE, FORBIDDEN_FUNCTION, "SELECT load_extension('evil')"),
            new Blocked("dblink", PG, FORBIDDEN_FUNCTION,
                    "SELECT * FROM dblink('host=other dbname=x', 'SELECT 1') AS t(a int)"),
            new Blocked("query_to_xml로 다른 SQL 실행", PG, FORBIDDEN_FUNCTION,
                    "SELECT query_to_xml('DELETE FROM actor RETURNING *', true, true, '')"),
            new Blocked("table_to_xml로 테이블 통째로 꺼내기", PG, FORBIDDEN_FUNCTION,
                    "SELECT table_to_xml('customer', true, true, '')"),
            new Blocked("set_config로 읽기 전용 끄기", PG, FORBIDDEN_FUNCTION,
                    "SELECT set_config('default_transaction_read_only', 'off', false)"),
            new Blocked("pg_terminate_backend", PG, FORBIDDEN_FUNCTION, "SELECT pg_terminate_backend(1234)"),
            new Blocked("nextval", PG, FORBIDDEN_FUNCTION, "SELECT nextval('actor_actor_id_seq')"),
            new Blocked("pg_advisory_lock", PG, FORBIDDEN_FUNCTION, "SELECT pg_advisory_lock(1)"),
            new Blocked("GET_LOCK", MY, FORBIDDEN_FUNCTION, "SELECT GET_LOCK('x', 10)"),
            // ── 금지 테이블 ──
            new Blocked("pg_shadow", PG, FORBIDDEN_TABLE, "SELECT usename, passwd FROM pg_shadow"),
            new Blocked("pg_catalog.pg_authid", PG, FORBIDDEN_TABLE, "SELECT rolpassword FROM pg_catalog.pg_authid"),
            new Blocked("서브쿼리 안의 pg_authid", PG, FORBIDDEN_TABLE,
                    "SELECT * FROM actor WHERE first_name IN (SELECT rolname FROM pg_authid)"),
            new Blocked("mysql.user", MY, FORBIDDEN_TABLE, "SELECT user, authentication_string FROM mysql.user"),
            new Blocked("sqlite_dbpage", LITE, FORBIDDEN_TABLE, "SELECT * FROM sqlite_dbpage"),
            // ── DB마다 해석이 갈리는 문법 ──
            new Blocked("역슬래시로 문자열 끝 숨기기", MY_PG, AMBIGUOUS_SYNTAX,
                    "SELECT 'a\\' , pg_sleep(10) -- '"),
            new Blocked("E 문자열의 역슬래시", PG, AMBIGUOUS_SYNTAX, "SELECT E'\\x41'"),
            new Blocked("달러 따옴표 문자열", PG, AMBIGUOUS_SYNTAX, "SELECT $$text$$"),
            new Blocked("DO 블록", PG, AMBIGUOUS_SYNTAX, "DO $x$ BEGIN DELETE FROM actor; END $x$"),
            new Blocked("MySQL의 공백 없는 --", MY, AMBIGUOUS_SYNTAX, "SELECT 1 --1"),
            new Blocked("MySQL --로 서브쿼리 숨기기", MY, AMBIGUOUS_SYNTAX,
                    "SELECT 1 --(SELECT authentication_string FROM mysql.user LIMIT 1)"),
            new Blocked("NUL 문자", ALL, AMBIGUOUS_SYNTAX, "SELECT 1\0; DROP TABLE actor"),
            new Blocked("숫자 뒤에 붙인 달러 따옴표", PG, AMBIGUOUS_SYNTAX, "SELECT 1$$ -- $$, pg_sleep(10)"),
            new Blocked("// 뒤에 숨긴 코드", ALL, AMBIGUOUS_SYNTAX, "SELECT 1 // , pg_sleep(10)"),
            new Blocked("주석으로 쪼갠 함수 이름 뒤 sleep", MY, FORBIDDEN_FUNCTION, "SELECT SLEEP/**/(10)"),
            // ── 닫히지 않음·빈 SQL·분석 불가 ──
            new Blocked("닫히지 않은 문자열", ALL, UNTERMINATED, "SELECT 'abc FROM actor"),
            new Blocked("닫히지 않은 주석", ALL, UNTERMINATED, "SELECT 1 /* 설명"),
            new Blocked("닫히지 않은 큰따옴표", ALL, UNTERMINATED, "SELECT \"abc FROM actor"),
            new Blocked("빈 문자열", ALL, EMPTY, ""),
            new Blocked("공백만", ALL, EMPTY, "   \n\t "),
            new Blocked("주석만", ALL, EMPTY, "-- 설명만 있음\n/* 주석 */"),
            new Blocked("세미콜론만", ALL, EMPTY, ";"),
            new Blocked("조건이 빠진 WHERE", ALL, PARSE_ERROR, "SELECT * FROM actor WHERE"),
            new Blocked("SELECT 오타", ALL, NOT_SELECT, "SELEC * FROM actor"),
    };

    private final SqlGuard guard = new SqlGuard();

    static Stream<Arguments> allowedCases() {
        return Stream.of(ALLOWED).flatMap(c -> c.kinds().stream()
                .map(k -> Arguments.argumentSet("[" + k.displayName() + "] " + c.title(), k, c.sql())));
    }

    static Stream<Arguments> blockedCases() {
        return Stream.of(BLOCKED).flatMap(c -> c.kinds().stream()
                .map(k -> Arguments.argumentSet("[" + k.displayName() + "] " + c.title(), k, c.sql(), c.reason())));
    }

    @ParameterizedTest
    @MethodSource("allowedCases")
    void allows(DbKind kind, String sql) {
        SqlGuard.CheckedQuery checked = guard.check(sql, kind);
        assertThat(checked.select()).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("blockedCases")
    void blocks(DbKind kind, String sql, RejectReason reason) {
        QueryRejectedException e = catchThrowableOfType(QueryRejectedException.class, () -> guard.check(sql, kind));

        assertThat(e).as("막혀야 하는데 통과함").isNotNull();
        assertThat(e.reason()).as(e.getMessage()).isEqualTo(reason);
        assertThat(e.getMessage()).startsWith("[" + reason.title() + "]");
    }

    // ── 주석 제거: 검사한 SQL과 DB가 실행하는 SQL을 같게 만든다 ──

    @Test
    void stripsCommentsAndTrailingSemicolon() {
        var checked = guard.check("-- 배우 목록\nSELECT /* 이름 */ first_name FROM actor; ", SQLITE);

        assertThat(checked.sql()).isEqualTo("SELECT   first_name FROM actor");
    }

    @Test
    void mysqlExecutableCommentIsRemovedNotExecuted() {
        // MySQL은 /*! ... */ 안을 실행한다. 지운 SQL을 실행하므로 숨긴 DROP은 DB에 전달되지 않는다.
        var checked = guard.check("SELECT 1 /*! ; DROP TABLE actor */", MYSQL);

        assertThat(checked.sql()).isEqualTo("SELECT 1");
    }

    @Test
    void mysqlHashCommentIsRemoved() {
        assertThat(guard.check("SELECT 1 # 설명; DROP TABLE actor\n", MYSQL).sql()).isEqualTo("SELECT 1");
    }

    @Test
    void postgresNestedCommentIsRemovedWhole() {
        // PostgreSQL은 주석 안에 주석을 넣을 수 있다. 바깥 주석이 끝날 때까지 지운다.
        var checked = guard.check("SELECT /* a /* b */ pg_sleep(10) */ 1", POSTGRESQL);

        assertThat(checked.sql()).isEqualTo("SELECT   1");
    }

    @Test
    void mysqlBlockCommentDoesNotNest() {
        // MySQL·SQLite에서는 첫 */ 에서 주석이 끝난다. 그 뒤의 SLEEP은 코드이므로 검사에 걸려야 한다.
        assertThatThrownBy(() -> guard.check("SELECT /* a /* b */ SLEEP(10) /* */", MYSQL))
                .isInstanceOf(QueryRejectedException.class)
                .extracting(e -> ((QueryRejectedException) e).reason())
                .isEqualTo(FORBIDDEN_FUNCTION);
    }

    @Test
    void keepsCommentMarkersInsideStrings() {
        var sql = "SELECT 'a -- b /* c */' AS s FROM actor";

        assertThat(guard.check(sql, POSTGRESQL).sql()).isEqualTo(sql);
    }

    @Test
    void reportsTablesAndFunctions() {
        var checked = guard.check(
                "SELECT UPPER(c.first_name), COUNT(*) FROM customer c JOIN rental r ON c.customer_id = r.customer_id GROUP BY 1",
                SQLITE);

        assertThat(checked.tables()).containsExactlyInAnyOrder("customer", "rental");
        assertThat(checked.functions()).contains("upper", "count");
    }

    @Test
    void rejectsTooLongSql() {
        String sql = "SELECT " + "1 + ".repeat(SqlGuard.MAX_SQL_LENGTH / 4) + "1";

        assertThatThrownBy(() -> guard.check(sql, SQLITE))
                .isInstanceOf(QueryRejectedException.class)
                .hasMessageContaining("20000");
    }

    @Test
    void caseTableIsLargeEnough() {
        long blockedRuns = blockedCases().count();
        long allowedRuns = allowedCases().count();
        assertThat(BLOCKED.length).as("막아야 할 사례").isGreaterThanOrEqualTo(50);
        assertThat(blockedRuns + allowedRuns).as("DB별 실행 수").isGreaterThan(150);
    }
}
