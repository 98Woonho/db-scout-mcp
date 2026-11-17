package io.github._98woonho.dbscout.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github._98woonho.dbscout.config.DbScoutProperties;
import io.github._98woonho.dbscout.config.DbScoutProperties.ConnectionConfig;
import io.github._98woonho.dbscout.config.DbScoutProperties.Limits;

class ConnectionRegistryTest {

    private static final Limits LIMITS = new Limits(100, 1000, 10, 60, 200, 50000);

    private static ConnectionRegistry registry(ConnectionConfig... configs) {
        return new ConnectionRegistry(new DbScoutProperties(List.of(configs), LIMITS), () -> "jdbc:sqlite:demo.db");
    }

    @Test
    void usesDemoWhenNothingConfigured() {
        // application.yml 의 환경변수 자리는 URL이 비어 있으면 '설정 안 됨'으로 본다
        var r = registry(new ConnectionConfig("main", "", "", "", ""));

        assertThat(r.list()).singleElement().satisfies(e -> {
            assertThat(e.name()).isEqualTo("sakila");
            assertThat(e.demo()).isTrue();
            assertThat(e.kind()).isEqualTo(DbKind.SQLITE);
        });
        assertThat(r.get(null).name()).isEqualTo("sakila");
    }

    @Test
    void configuredConnectionsReplaceDemo() {
        var r = registry(
                new ConnectionConfig("shop", "jdbc:postgresql://localhost/shop", "reader", "secret", "쇼핑몰"),
                new ConnectionConfig(null, "jdbc:mysql://localhost/erp", "reader", "secret", null));

        assertThat(r.list()).extracting(ConnectionRegistry.Entry::name).containsExactly("shop", "db2");
        assertThat(r.list()).noneMatch(ConnectionRegistry.Entry::demo);
        assertThat(r.get("db2").kind()).isEqualTo(DbKind.MYSQL);
        assertThat(r.get("").name()).isEqualTo("shop"); // 비우면 첫 번째
    }

    @Test
    void unknownNameListsAvailableNames() {
        var r = registry(new ConnectionConfig("shop", "jdbc:postgresql://localhost/shop", null, null, null));

        assertThatThrownBy(() -> r.get("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shop");
    }

    @Test
    void rejectsUnsupportedDatabase() {
        assertThatThrownBy(() -> registry(new ConnectionConfig("x", "jdbc:oracle:thin:@host:1521:x", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는");
    }

    @Test
    void rejectsDuplicateNames() {
        assertThatThrownBy(() -> registry(
                new ConnectionConfig("a", "jdbc:sqlite:a.db", null, null, null),
                new ConnectionConfig("a", "jdbc:sqlite:b.db", null, null, null)))
                .hasMessageContaining("같은 이름");
    }

    @Test
    void secretsNeverAppearInToString() {
        var config = new ConnectionConfig("shop", "jdbc:postgresql://db.internal/shop", "reader", "s3cr3t", null);
        var entry = registry(config).get("shop");

        assertThat(config.toString()).doesNotContain("s3cr3t", "db.internal");
        assertThat(entry.toString()).doesNotContain("s3cr3t", "db.internal");
    }
}
