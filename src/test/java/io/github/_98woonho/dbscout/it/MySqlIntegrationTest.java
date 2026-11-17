package io.github._98woonho.dbscout.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.github._98woonho.dbscout.connection.DbKind;
import io.github._98woonho.dbscout.query.QueryResult;

/** 실제 MySQL 8 통합 테스트. DB_SCOUT_IT_MYSQL_URL 이 있을 때만 실행. */
@EnabledIfEnvironmentVariable(named = "DB_SCOUT_IT_MYSQL_URL", matches = ".+")
class MySqlIntegrationTest extends DatabaseIntegrationTest {

    private final IntegrationStack stack = IntegrationStack.fromEnv("MYSQL");

    @Override
    IntegrationStack stack() {
        return stack;
    }

    @Override
    DbKind kind() {
        return DbKind.MYSQL;
    }

    @Test
    void jsonFunctionsDoNotBypassMasking() {
        QueryResult r = stack.executor().run(null,
                "SELECT JSON_OBJECT('e', email) AS j FROM customer WHERE customer_id = 1", null, null);

        assertThat(r.rows().getFirst().getFirst().toString()).doesNotContain("SMITH").endsWith("***");
    }
}
