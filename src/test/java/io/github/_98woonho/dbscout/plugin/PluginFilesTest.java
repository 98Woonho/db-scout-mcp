package io.github._98woonho.dbscout.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

/**
 * 플러그인 파일(plugin/)과 배포 파일(npm/, server.json)을 검사한다.
 * 평가(claude plugin eval)는 비용이 들어서, 형식 오류는 여기서 무료로 먼저 잡는다.
 */
class PluginFilesTest {

    static final Path PLUGIN = Path.of("plugin");
    static final String PREFIX = "mcp__plugin_db-scout_db-scout__";
    static final Set<String> SERVER_TOOLS = Set.of("list_connections", "list_tables", "describe_table", "search_schema",
            "find_join_path", "sample_rows", "profile_column", "explain_query", "run_query");
    static final Set<String> GRADER_TYPES = Set.of("llm", "regex", "tool_used", "tool_order", "file_exists", "baseline");
    static final Pattern FRONTMATTER = Pattern.compile("\\A---\\R([\\s\\S]*?)\\R---");

    // ───────── 버전과 이름 ─────────

    @Test
    void pluginVersionMatchesGradleVersion() throws IOException {
        Properties gradle = new Properties();
        try (var in = Files.newInputStream(Path.of("gradle.properties"))) {
            gradle.load(in);
        }
        Map<?, ?> plugin = new Yaml().load(Files.readString(PLUGIN.resolve(".claude-plugin/plugin.json")));

        assertThat(plugin.get("version")).as("plugin.json 의 version 은 gradle.properties 와 같아야 합니다")
                .isEqualTo(gradle.getProperty("version"));
        assertThat(plugin.get("name")).isEqualTo("db-scout");
    }

    @Test
    void mcpServerIsNamedDbScout() throws IOException {
        Map<?, ?> mcp = new Yaml().load(Files.readString(PLUGIN.resolve(".mcp.json")));
        Map<?, ?> servers = (Map<?, ?>) mcp.get("mcpServers");

        assertThat(servers.keySet().stream().map(Object::toString).toList()).containsExactly("db-scout"); // 도구 이름 접두사 mcp__plugin_db-scout_db-scout__
    }

    // ───────── 실행기 Launch.java ─────────

    @Test
    void launcherCompilesAndMapsPluginConfig(@TempDir Path out) throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null, "-d", out.toString(), PLUGIN.resolve("bin/Launch.java").toString());
        assertThat(rc).as("Launch.java 컴파일").isZero();

        try (URLClassLoader loader = new URLClassLoader(new URL[] {out.toUri().toURL()})) {
            Class<?> launch = loader.loadClass("Launch");
            Method apply = launch.getDeclaredMethod("applyPluginConfig", Map.class);
            apply.setAccessible(true);
            Map<String, String> env = new HashMap<>(Map.of(
                    "DB_SCOUT_PLUGIN_URL", "jdbc:postgresql://localhost/shop",
                    "DB_SCOUT_PLUGIN_PASSWORD", "${user_config.db_password}", // 설정 안 함 → 치환 안 된 자리표시자
                    "DB_SCOUT_PLUGIN_USERNAME", "  ",
                    "DB_SCOUT_PASSWORD", "from-shell"));
            apply.invoke(null, env);

            assertThat(env).containsEntry("DB_SCOUT_URL", "jdbc:postgresql://localhost/shop")
                    .containsEntry("DB_SCOUT_PASSWORD", "from-shell") // 셸 환경변수를 덮어쓰지 않는다
                    .doesNotContainKeys("DB_SCOUT_USERNAME", "DB_SCOUT_PLUGIN_URL", "DB_SCOUT_PLUGIN_PASSWORD");
        }
    }

    // ───────── npm 실행기(npm/)와 MCP 레지스트리(server.json) ─────────

    @Test
    void npmPackageAndRegistryMatchGradleVersion() throws IOException {
        Properties gradle = new Properties();
        try (var in = Files.newInputStream(Path.of("gradle.properties"))) {
            gradle.load(in);
        }
        String version = gradle.getProperty("version");
        Map<?, ?> pkg = new Yaml().load(Files.readString(Path.of("npm/package.json")));
        Map<?, ?> server = new Yaml().load(Files.readString(Path.of("server.json")));
        Map<?, ?> npm = (Map<?, ?>) ((List<?>) server.get("packages")).getFirst();

        assertThat(pkg.get("version")).as("npm/package.json 의 version").isEqualTo(version);
        assertThat(server.get("version")).as("server.json 의 version").isEqualTo(version);
        assertThat(npm.get("version")).as("server.json packages[0].version").isEqualTo(version);
        // 레지스트리는 npm 패키지의 mcpName 이 server.json 의 name 과 같은지로 소유를 확인한다
        assertThat(pkg.get("mcpName")).isEqualTo(server.get("name"));
        assertThat(npm.get("identifier")).isEqualTo(pkg.get("name"));
        assertThat(npm.get("registryType")).isEqualTo("npm");
        assertThat((String) server.get("description")).as("레지스트리 설명은 100자 이하").hasSizeLessThanOrEqualTo(100);
    }

    @Test
    void registryEnvironmentVariablesExistInServerConfig() throws IOException {
        Map<?, ?> server = new Yaml().load(Files.readString(Path.of("server.json")));
        Map<?, ?> npm = (Map<?, ?>) ((List<?>) server.get("packages")).getFirst();
        String config = Files.readString(Path.of("src/main/resources/application.yml"));

        for (Object env : (List<?>) npm.get("environmentVariables")) {
            String name = (String) ((Map<?, ?>) env).get("name");
            assertThat(config).as("application.yml 이 " + name + " 을 읽어야 합니다").contains("${" + name + ":");
        }
    }

    @Test
    void launchersShareReleaseLocationAndCache() throws IOException {
        String java = Files.readString(PLUGIN.resolve("bin/Launch.java"));
        String js = Files.readString(Path.of("npm/launch.js"));

        assertThat(constant(js, "REPO")).isEqualTo(constant(java, "REPO")).isEqualTo("98Woonho/db-scout-mcp");
        assertThat(constant(js, "JAR")).isEqualTo(constant(java, "JAR")).isEqualTo("db-scout-mcp.jar");
        // 같은 주소에서 받고, 같은 폴더(~/.db-scout/jars/<버전>/)에 보관한다
        assertThat(java).contains("/releases/download/v", "\".db-scout\", \"jars\"");
        assertThat(js).contains("/releases/download/v", "\".db-scout\", \"jars\"");
        // 해시 파일로 확인한다
        assertThat(java).contains("JAR + \".sha256\"", "SHA-256");
        assertThat(js).contains("JAR + \".sha256\"", "createHash(\"sha256\")");
    }

    private static String constant(String source, String name) {
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*\"([^\"]+)\"").matcher(source);
        assertThat(m.find()).as(name + " 상수").isTrue();
        return m.group(1);
    }

    // ───────── Skill ─────────

    static Stream<Path> skills() throws IOException {
        try (Stream<Path> s = Files.list(PLUGIN.resolve("skills"))) {
            return s.map(p -> p.resolve("SKILL.md")).toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("skills")
    void skillHasNameAndDescription(Path skill) throws IOException {
        Map<?, ?> fm = frontmatter(skill);

        assertThat(fm.get("name")).isEqualTo(skill.getParent().getFileName().toString());
        assertThat((String) fm.get("description")).hasSizeGreaterThan(100).hasSizeLessThan(1024);
    }

    // ───────── 평가 문제 ─────────

    static Stream<Arguments> evalFiles() throws IOException {
        try (Stream<Path> s = Files.walk(PLUGIN.resolve("evals"))) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("results"))
                    .map(p -> (Arguments) Arguments.argumentSet(PLUGIN.resolve("evals").relativize(p).toString(), p))
                    .toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("evalFiles")
    void evalFileIsValid(Path file) throws IOException {
        String name = file.getFileName().toString();
        if (name.equals("case.yaml")) {
            Map<?, ?> c = new Yaml().load(Files.readString(file));
            assertThat(c.get("schema_version")).isEqualTo("1.1");
            assertThat(c.get("name")).isEqualTo(file.getParent().getFileName().toString());
            assertThat(c.get("expected_outcome")).isNotNull();
            assertThat(file.resolveSibling("prompt.md")).exists();
            try (Stream<Path> g = Files.list(file.resolveSibling("graders"))) {
                assertThat(g.count()).as("채점 항목").isPositive();
            }
        } else if (name.equals("prompt.md")) {
            Map<?, ?> fm = frontmatter(file);
            List<String> allowed = ((List<?>) fm.get("allowed_tools")).stream().map(Object::toString).toList();
            assertThat(allowed).contains("Skill");
            allowed.stream().filter(t -> t.startsWith("mcp__")).forEach(this::assertServerTool);
            assertThat(SERVER_TOOLS).allSatisfy(t -> assertThat(allowed).contains(PREFIX + t));
            assertThat(body(file)).isNotBlank();
        } else {
            Map<?, ?> g = frontmatter(file);
            String type = (String) g.get("type");
            assertThat(type).isIn(GRADER_TYPES);
            switch (type) {
                case "llm" -> assertThat(body(file).length() + String.valueOf(g.get("criteria")).length()).isGreaterThan(30);
                case "regex" -> {
                    assertThat(g.get("match")).isIn("contains", "not_contains");
                    assertJavaScriptCompatible((String) g.get("pattern"));
                }
                case "tool_used" -> {
                    assertServerToolOrBuiltin((String) g.get("tool"));
                    if (g.get("input_match") != null) assertJavaScriptCompatible((String) g.get("input_match"));
                }
                case "tool_order" -> {
                    assertServerToolOrBuiltin((String) g.get("before"));
                    assertServerToolOrBuiltin((String) g.get("after"));
                }
                default -> { }
            }
        }
    }

    /**
     * 평가 도구는 JavaScript 정규식을 쓴다. Java는 받지만 JavaScript는 못 받는 문법을 막는다.
     * 특히 (?i) 같은 인라인 플래그: 대소문자 무시는 regex 채점의 flags: i 나 [Ss][Uu][Mm] 처럼 쓴다.
     */
    private static void assertJavaScriptCompatible(String regex) {
        Pattern.compile(regex);
        assertThat(regex).as("JavaScript 정규식은 (?i) 같은 인라인 플래그를 지원하지 않습니다")
                .doesNotContainPattern("\\(\\?[imsxuU-]+[):]");
        assertThat(regex).as("JavaScript 정규식에 없는 문법").doesNotContain("\\A", "\\Z", "\\z", "*+", "++");
    }

    private void assertServerToolOrBuiltin(String tool) {
        if (tool.startsWith("mcp__")) assertServerTool(tool);
        else assertThat(tool).isIn("Skill", "Read");
    }

    private void assertServerTool(String tool) {
        assertThat(tool).startsWith(PREFIX);
        assertThat(tool.substring(PREFIX.length())).as("서버에 있는 도구여야 합니다").isIn(SERVER_TOOLS);
    }

    static Map<?, ?> frontmatter(Path file) throws IOException {
        Matcher m = FRONTMATTER.matcher(Files.readString(file));
        assertThat(m.find()).as(file + " 의 frontmatter(--- ... ---)").isTrue();
        return new Yaml().load(m.group(1));
    }

    static String body(Path file) throws IOException {
        Matcher m = FRONTMATTER.matcher(Files.readString(file));
        return m.find() ? Files.readString(file).substring(m.end()).strip() : "";
    }
}
