import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claude Code 플러그인용 db-scout MCP 서버 실행기.
 *
 * <p>{@code java Launch.java <플러그인 폴더>} 로 실행된다(.mcp.json). Java 소스 파일 하나를 바로 실행하는 기능이라
 * 사용자는 Java 21만 있으면 되고 Node.js 등은 필요 없다.
 *
 * <ol>
 *   <li>저장소 안에서 실행되면(개발·평가) 로컬에서 빌드한 build/libs/db-scout-mcp.jar 를 쓴다.</li>
 *   <li>아니면 플러그인 버전과 같은 버전의 jar 를 GitHub Release 에서 받아 SHA-256 을 확인하고
 *       사용자 폴더(~/.db-scout/jars)에 보관한다. 다음부터는 받지 않는다.</li>
 *   <li>플러그인 설정(userConfig)을 서버의 환경변수로 옮겨 jar 를 실행하고, 표준입출력을 그대로 넘긴다.</li>
 * </ol>
 *
 * <p>표준출력은 MCP 메시지 전용이므로 이 실행기의 안내는 모두 표준에러로 낸다.
 */
public class Launch {

    static final String REPO = "98Woonho/db-scout-mcp";
    static final String JAR = "db-scout-mcp.jar";
    static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*\"(\\d+\\.\\d+\\.\\d+)\"");

    /** 플러그인 설정 → 서버 환경변수. 비어 있거나 치환되지 않은 자리표시자(${...})면 옮기지 않는다. */
    static final Map<String, String> CONFIG_TO_ENV = Map.of(
            "DB_SCOUT_PLUGIN_URL", "DB_SCOUT_URL",
            "DB_SCOUT_PLUGIN_USERNAME", "DB_SCOUT_USERNAME",
            "DB_SCOUT_PLUGIN_PASSWORD", "DB_SCOUT_PASSWORD",
            "DB_SCOUT_PLUGIN_MASK_COLUMNS", "DB_SCOUT_MASK_COLUMNS",
            "DB_SCOUT_PLUGIN_HIDDEN_TABLES", "DB_SCOUT_HIDDEN_TABLES");

    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() < 21) {
            fail("Java 21 이상이 필요합니다. 지금 Java: " + Runtime.version());
        }
        if (args.length < 1) fail("사용법: java Launch.java <플러그인 폴더>");
        Path pluginRoot = Path.of(args[0]).toAbsolutePath().normalize();

        Path jar = localBuild(pluginRoot);
        if (jar == null) jar = cachedRelease(version(pluginRoot));

        ProcessBuilder pb = new ProcessBuilder(javaExecutable(), "-jar", jar.toString()).inheritIO();
        applyPluginConfig(pb.environment());
        Process child = pb.start();
        Runtime.getRuntime().addShutdownHook(new Thread(child::destroy));
        System.exit(child.waitFor());
    }

    /** 저장소 안의 플러그인이면 로컬 빌드 jar. 설치된 플러그인(캐시 복사본)에는 상위에 저장소가 없다. */
    static Path localBuild(Path pluginRoot) throws IOException {
        Path repo = pluginRoot.getParent();
        if (repo == null) return null;
        Path settings = repo.resolve("settings.gradle.kts");
        Path jar = repo.resolve("build").resolve("libs").resolve(JAR);
        if (Files.isRegularFile(settings) && Files.readString(settings).contains("\"db-scout-mcp\"")) {
            if (!Files.isRegularFile(jar)) {
                fail("저장소 안에서 실행 중인데 빌드된 jar 가 없습니다: " + jar + " (.\\gradlew.bat bootJar 를 먼저 실행하세요)");
            }
            log("저장소의 로컬 빌드를 실행합니다: " + jar);
            return jar;
        }
        return null;
    }

    static String version(Path pluginRoot) throws IOException {
        Path manifest = pluginRoot.resolve(".claude-plugin").resolve("plugin.json");
        Matcher m = VERSION.matcher(Files.readString(manifest));
        if (!m.find()) fail("plugin.json 의 version 을 읽지 못했습니다: " + manifest);
        return m.group(1);
    }

    /** ~/.db-scout/jars/<버전>/db-scout-mcp.jar. 없으면 GitHub Release 에서 받아 해시를 확인한다. */
    static Path cachedRelease(String version) throws Exception {
        Path dir = Path.of(System.getProperty("user.home"), ".db-scout", "jars", version);
        Path jar = dir.resolve(JAR);
        if (Files.isRegularFile(jar)) return jar;

        String base = "https://github.com/" + REPO + "/releases/download/v" + version + "/";
        log("db-scout " + version + " 서버를 처음 한 번 내려받습니다(약 40MB)...");
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        String expected = new String(download(http, base + JAR + ".sha256")).trim().split("\\s+")[0].toLowerCase();
        if (!expected.matches("[0-9a-f]{64}")) fail("해시 파일 형식이 올바르지 않습니다: " + base + JAR + ".sha256");

        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "download-", ".tmp");
        try {
            HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(base + JAR))
                    .timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) fail("내려받기 실패(HTTP " + res.statusCode() + "): " + base + JAR);
            try (InputStream in = res.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(tmp)));
            if (!actual.equals(expected)) fail("내려받은 파일의 해시가 다릅니다. 예상 " + expected + ", 실제 " + actual);
            Files.move(tmp, jar, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        log("받았습니다: " + jar);
        return jar;
    }

    static byte[] download(HttpClient http, String url) throws Exception {
        HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) {
            fail("내려받기 실패(HTTP " + res.statusCode() + "): " + url + " (인터넷 연결이나 플러그인 버전을 확인하세요)");
        }
        return res.body();
    }

    static void applyPluginConfig(Map<String, String> env) {
        for (Map.Entry<String, String> e : CONFIG_TO_ENV.entrySet()) {
            String value = env.remove(e.getKey());
            if (value != null && !value.isBlank() && !value.strip().startsWith("${")) {
                env.put(e.getValue(), value.strip());
            }
        }
    }

    static String javaExecutable() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    static void log(String message) {
        System.err.println("[db-scout] " + message);
    }

    static void fail(String message) {
        log(message);
        System.exit(1);
    }
}
