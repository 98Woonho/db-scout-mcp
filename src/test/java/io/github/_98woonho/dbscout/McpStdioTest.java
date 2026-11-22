package io.github._98woonho.dbscout;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

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

        assertThat(tools).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder(
                "list_connections", "list_tables", "describe_table", "search_schema", "find_join_path",
                "sample_rows", "profile_column", "explain_query", "run_query");
        assertThat(tools).allSatisfy(t -> {
            assertThat(t.annotations()).as(t.name()).isNotNull();
            assertThat(t.annotations().readOnlyHint()).as(t.name()).isTrue();
            assertThat(t.annotations().destructiveHint()).as(t.name()).isFalse();
            assertThat(t.title()).as(t.name()).isNotBlank();
        });
    }

    /**
     * 포트폴리오는 서버를 실행하지 않고 showcase/tools.json 을 읽어 도구 목록을 보여 준다.
     * 이 파일이 실제 서버와 어긋나면 실패한다. 도구를 바꾸거나 버전을 올렸으면
     * {@code .\gradlew.bat test -PupdateShowcase} 로 파일을 다시 쓴다.
     */
    @Test
    void showcaseToolsFileMatchesServer() throws Exception {
        Path file = Path.of("showcase", "tools.json");
        JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("$comment", "실제 서버(jar)의 도구 목록. 포트폴리오가 빌드할 때 읽는다. "
                + "McpStdioTest가 서버와 같은지 확인하고, .\\gradlew.bat test -PupdateShowcase 로 다시 만든다.");
        Map<String, Object> server = new LinkedHashMap<>(); // 순서를 고정해 다시 만들어도 같은 파일이 나오게
        server.put("name", init.serverInfo().name());
        server.put("version", init.serverInfo().version());
        actual.put("server", server);
        actual.put("tools", client.listTools().tools().stream()
                .sorted(java.util.Comparator.comparing(McpSchema.Tool::name))
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", t.name());
                    m.put("title", t.title());
                    m.put("description", t.description());
                    return m;
                })
                .toList());
        JsonNode expected = json.valueToTree(actual);

        if (Boolean.getBoolean("dbscout.updateShowcase")) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.writeValueAsString(expected) + "\n");
        }
        assertThat(file).as("showcase/tools.json 이 없습니다. .\\gradlew.bat test -PupdateShowcase 로 만드세요").exists();
        assertThat(json.readTree(Files.readString(file)))
                .as("showcase/tools.json 이 실제 서버와 다릅니다. .\\gradlew.bat test -PupdateShowcase 로 다시 만드세요")
                .isEqualTo(expected);
    }

    private static McpSchema.CallToolResult call(String tool, Map<String, Object> args) {
        return client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(args).build());
    }

    private static String text(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text())
                .reduce("", String::concat);
    }

    @Test
    void listConnectionsShowsDemoWithoutSecrets() {
        McpSchema.CallToolResult result = call("list_connections", Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"sakila\"", "SQLite", "\"demo\":true", "maxRows");
        assertThat(text(result)).doesNotContain("jdbc:", "password");
    }

    @Test
    void runQueryMasksOverStdio() {
        McpSchema.CallToolResult result = call("run_query",
                Map.of("sql", "SELECT customer_id, email FROM customer ORDER BY customer_id", "maxRows", 2));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("M***@sakilacustomer.org", "\"truncated\":true").doesNotContain("MARY.SMITH");
    }

    @Test
    void rejectedQueryIsAToolError() {
        McpSchema.CallToolResult result = call("run_query", Map.of("sql", "DELETE FROM payment"));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).startsWith("[SELECT가 아닌 문장]");
    }

    @Test
    void optionalArgumentsCanBeOmitted() {
        assertThat(call("list_tables", Map.of()).isError()).isFalse();
        assertThat(call("describe_table", Map.of("table", "film")).isError()).isFalse();
        assertThat(call("find_join_path", Map.of("fromTable", "film", "toTable", "payment")).isError()).isFalse();
    }

    @Test
    void demoDbWasExtracted() throws Exception {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "db-scout");
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).anyMatch(n -> n.matches("sakila-[0-9a-f]{12}\\.db"));
        }
    }
}
