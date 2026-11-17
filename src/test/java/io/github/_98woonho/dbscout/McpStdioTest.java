package io.github._98woonho.dbscout;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * 빌드된 jar를 MCP 클라이언트(Claude Code와 같은 방식)로 실행해 STDIO로 대화해 본다.
 * 표준출력에 로그가 섞이면 여기서 바로 실패한다.
 */
class McpStdioTest {

    private static McpSyncClient client;
    private static McpSchema.InitializeResult init;

    @BeforeAll
    static void start() {
        Path jar = Path.of(System.getProperty("dbscout.jar", "build/libs/db-scout-mcp.jar"));
        assertThat(jar).as("bootJar 를 먼저 빌드해야 합니다").exists();
        String java = ProcessHandle.current().info().command().orElse("java");

        ServerParameters params = ServerParameters.builder(java)
                .args("-jar", jar.toString())
                .env(Map.of("DB_SCOUT_URL", "")) // 개발 PC의 환경변수와 무관하게 데모 DB로 실행
                .build();
        client = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(60))
                .build();
        init = client.initialize();
    }

    @AfterAll
    static void stop() {
        if (client != null) client.closeGracefully();
    }

    @Test
    void reportsServerInfo() {
        assertThat(init.serverInfo().name()).isEqualTo("db-scout");
        assertThat(init.serverInfo().version()).matches("\\d+\\.\\d+\\.\\d+.*");
        assertThat(init.instructions()).contains("읽기 전용");
    }

    @Test
    void listsReadOnlyTools() {
        List<McpSchema.Tool> tools = client.listTools().tools();

        assertThat(tools).extracting(McpSchema.Tool::name).contains("list_connections");
        assertThat(tools).allSatisfy(t -> {
            assertThat(t.annotations()).as(t.name()).isNotNull();
            assertThat(t.annotations().readOnlyHint()).as(t.name()).isTrue();
            assertThat(t.annotations().destructiveHint()).as(t.name()).isFalse();
            assertThat(t.title()).as(t.name()).isNotBlank();
        });
    }

    @Test
    void listConnectionsShowsDemoWithoutSecrets() {
        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("list_connections").arguments(Map.of()).build());

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text())
                .reduce("", String::concat);
        assertThat(text).contains("\"sakila\"", "SQLite", "\"demo\":true", "maxRows");
        assertThat(text).doesNotContain("jdbc:", "password");
    }

    @Test
    void demoDbWasExtracted() throws Exception {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "db-scout");
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).anyMatch(n -> n.matches("sakila-[0-9a-f]{12}\\.db"));
        }
    }
}
