package io.github._98woonho.dbscout.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import io.github._98woonho.dbscout.config.DbScoutProperties;
import io.github._98woonho.dbscout.config.DbScoutProperties.ConnectionConfig;
import io.github._98woonho.dbscout.config.DbScoutProperties.Limits;
import io.github._98woonho.dbscout.connection.ConnectionRegistry;
import io.github._98woonho.dbscout.connection.ReadOnlyConnections;
import io.github._98woonho.dbscout.connection.SakilaDemo;
import io.github._98woonho.dbscout.guard.SqlGuard;
import io.github._98woonho.dbscout.query.ExplainService;
import io.github._98woonho.dbscout.query.QueryExecutor;
import io.github._98woonho.dbscout.schema.SchemaService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Sakila 데모 DB로 도구 객체들을 만든다(Spring 없이). */
final class ToolsFixture {

    static final Limits LIMITS = new Limits(100, 1000, 10, 60, 200, 50_000);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    record Tools(ConnectionTools connection, SchemaTools schema, DataTools data, QueryTools query) {
    }

    private ToolsFixture() {
    }

    /** 내장 데모 DB 그대로. */
    static Tools demo(Path dir) {
        Path db = SakilaDemo.extract(dir);
        return build(new DbScoutProperties(List.of(), LIMITS), () -> "jdbc:sqlite:" + db);
    }

    /** Sakila 파일을 일반 접속으로 등록하고 일부 테이블을 숨긴다. */
    static Tools withHidden(Path dir, List<String> hidden) {
        Path db = SakilaDemo.extract(dir);
        var config = new ConnectionConfig("shop", "jdbc:sqlite:" + db, null, null, "숨김 테스트",
                SakilaDemo.MASK_COLUMNS, List.of(), hidden);
        return build(new DbScoutProperties(List.of(config), LIMITS), () -> "unused");
    }

    private static Tools build(DbScoutProperties props, java.util.function.Supplier<String> demoUrl) {
        var registry = new ConnectionRegistry(props, demoUrl);
        var connections = new ReadOnlyConnections();
        var guard = new SqlGuard();
        var executor = new QueryExecutor(registry, connections, guard, props);
        var schema = new SchemaService(registry, connections);
        return new Tools(new ConnectionTools(registry, props), new SchemaTools(schema),
                new DataTools(schema, executor), new QueryTools(executor, new ExplainService(registry, connections, guard, props)));
    }

    static String text(CallToolResult result) {
        return result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(c -> ((TextContent) c).text())
                .reduce("", String::concat);
    }

    /** 성공 응답의 JSON. 실패 응답이면 메시지와 함께 테스트를 실패시킨다. */
    static JsonNode json(CallToolResult result) {
        assertThat(result.isError()).as(text(result)).isFalse();
        return JSON.readTree(text(result));
    }

    /** 실패 응답의 메시지. */
    static String error(CallToolResult result) {
        assertThat(result.isError()).as(text(result)).isTrue();
        return text(result);
    }
}
