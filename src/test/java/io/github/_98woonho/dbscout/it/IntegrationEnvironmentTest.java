package io.github._98woonho.dbscout.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * CI에서 통합 테스트가 '조용히 건너뛰어지는' 일을 막는다.
 * DB_SCOUT_IT_REQUIRED=true 인데 접속 정보가 없으면 실패한다.
 */
@EnabledIfEnvironmentVariable(named = "DB_SCOUT_IT_REQUIRED", matches = "true")
class IntegrationEnvironmentTest {

    @Test
    void bothDatabasesAreConfigured() {
        assertThat(System.getenv("DB_SCOUT_IT_MYSQL_URL")).as("DB_SCOUT_IT_MYSQL_URL").isNotBlank();
        assertThat(System.getenv("DB_SCOUT_IT_POSTGRES_URL")).as("DB_SCOUT_IT_POSTGRES_URL").isNotBlank();
    }
}
