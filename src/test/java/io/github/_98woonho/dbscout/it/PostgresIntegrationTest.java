package io.github._98woonho.dbscout.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.github._98woonho.dbscout.connection.DbKind;
import io.github._98woonho.dbscout.query.QueryFailedException;
import io.github._98woonho.dbscout.query.QueryResult;

/** 실제 PostgreSQL 16 통합 테스트. DB_SCOUT_IT_POSTGRES_URL 이 있을 때만 실행. */
@EnabledIfEnvironmentVariable(named = "DB_SCOUT_IT_POSTGRES_URL", matches = ".+")
class PostgresIntegrationTest extends DatabaseIntegrationTest {

    private final IntegrationStack stack = IntegrationStack.fromEnv("POSTGRES");

    @Override
    IntegrationStack stack() {
        return stack;
    }

    @Override
    DbKind kind() {
        return DbKind.POSTGRESQL;
    }

    @Test
    void wholeRowReferenceIsMasked() {
        // PostgreSQL에서는 SELECT c FROM customer c 가 행 전체를 (1,1,MARY,...,mary@...) 하나의 값으로 돌려준다
        QueryResult r = stack.executor().run(null, "SELECT c FROM customer c WHERE c.customer_id = 1", null, null);

        assertThat(r.rows().getFirst().getFirst().toString()).doesNotContain("SMITH").endsWith("***");
    }

    @Test
    void rowToJsonIsMasked() {
        QueryResult r = stack.executor().run(null,
                "SELECT row_to_json(c) AS j FROM customer c WHERE c.customer_id = 1", null, null);

        assertThat(r.rows().getFirst().getFirst().toString()).doesNotContain("SMITH");
    }

    @Test
    void errorMessagesDoNotLeakValues() {
        // 형 변환 오류 메시지에는 원래 값이 들어간다: invalid input syntax for type integer: "MARY.SMITH@..."
        QueryFailedException e = catchThrowableOfType(QueryFailedException.class, () -> stack.executor()
                .run(null, "SELECT CAST(email AS INTEGER) FROM customer WHERE customer_id = 1", null, null));

        assertThat(e).isNotNull();
        assertThat(e.getMessage()).contains("integer").contains("(값 숨김)").doesNotContain("SMITH");
    }
}
