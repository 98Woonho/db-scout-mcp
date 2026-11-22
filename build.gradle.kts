import java.net.URI
import java.security.MessageDigest
import java.util.Properties

buildscript {
    dependencies {
        // 빌드 중에 Sakila 데모 DB(SQLite 파일)를 만들 때만 쓴다
        classpath("org.xerial:sqlite-jdbc:3.54.1.0")
    }
}

plugins {
    java
    id("org.springframework.boot") version "4.1.2"
}

group = "io.github.98woonho"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.2"))

    implementation("org.springframework.ai:spring-ai-starter-mcp-server")
    implementation("com.github.jsqlparser:jsqlparser:5.4")
    implementation("tools.jackson.core:jackson-databind") // 도구 응답 JSON (버전은 Spring Boot가 관리)

    implementation("org.xerial:sqlite-jdbc:3.54.1.0") // 읽기 전용 설정(SQLiteConfig)에 직접 쓴다
    runtimeOnly("com.mysql:mysql-connector-j:26.10.0")
    runtimeOnly("org.postgresql:postgresql:42.7.14")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.bootJar {
    archiveFileName = "db-scout-mcp.jar"
    from(files("LICENSE", "THIRD_PARTY_NOTICES.md")) { into("META-INF") }
}

tasks.jar {
    enabled = false // 실행용 jar(bootJar) 하나만 만든다
}

// 실제 jar를 MCP 클라이언트처럼 실행해 보는 테스트(McpStdioTest)가 jar 경로를 알 수 있게 한다
val bootJarFile = tasks.bootJar.flatMap { it.archiveFile }
tasks.test {
    useJUnitPlatform()
    inputs.file(bootJarFile)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Ddbscout.jar=${bootJarFile.get().asFile.absolutePath}")
    })
}

// 서버가 initialize 응답에 보낼 버전을 Gradle 버전과 맞춘다 (application.yml 의 "@version@")
tasks.processResources {
    val projectVersion = project.version.toString()
    inputs.property("version", projectVersion)
    filesMatching("application.yml") {
        filter<org.apache.tools.ant.filters.ReplaceTokens>("tokens" to mapOf("version" to projectVersion))
    }
}

// ─────────────────────────────────────────────────────────────
// Sakila 데모 DB
//   공개 샘플 DB Sakila의 SQLite판(jOOQ/sakila, BSD-2-Clause)을 고정 커밋에서 받아
//   SHA-256으로 확인한 뒤 SQLite 파일로 만들어 jar에 넣는다.
//   8.5MB짜리 SQL을 저장소에 넣지 않기 위해 빌드할 때 받는다.
// ─────────────────────────────────────────────────────────────
val sakilaCommit = "e089a5b1ec9af0df7a9c6a5d47d49fa1736a4e84"
val sakilaFiles = mapOf(
    "sqlite-sakila-schema.sql" to "b1db76b3a5192f98493901a9ac40fecb9dba918feb5604b7ef6b74230a385f12",
    "sqlite-sakila-insert-data.sql" to "4d69a56acbd3f60501e45737353707df0cf51fdb7fcab65cc9db3a72c8c85f34",
)
val sakilaSrcDir = layout.buildDirectory.dir("sakila/src")
val sakilaOutDir = layout.buildDirectory.dir("sakila/resources")

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

val downloadSakila = tasks.register("downloadSakila") {
    description = "Sakila SQLite 스크립트를 고정 커밋에서 내려받아 해시를 확인한다"
    inputs.property("commit", sakilaCommit)
    inputs.property("files", sakilaFiles)
    outputs.dir(sakilaSrcDir)
    doLast {
        val dir = sakilaSrcDir.get().asFile.apply { mkdirs() }
        for ((name, expected) in sakilaFiles) {
            val target = dir.resolve(name)
            if (target.exists() && sha256(target.readBytes()) == expected) continue
            val url = "https://raw.githubusercontent.com/jOOQ/sakila/$sakilaCommit/sqlite-sakila-db/$name"
            val bytes = URI(url).toURL().openStream().use { it.readBytes() }
            val actual = sha256(bytes)
            check(actual == expected) { "$name 해시 불일치: 예상 $expected, 실제 $actual" }
            target.writeBytes(bytes)
        }
    }
}

val buildSakilaDb = tasks.register("buildSakilaDb") {
    description = "Sakila SQL 스크립트로 데모용 SQLite 파일(sakila.db)을 만든다"
    dependsOn(downloadSakila)
    inputs.dir(sakilaSrcDir)
    outputs.dir(sakilaOutDir)
    doLast {
        val src = sakilaSrcDir.get().asFile
        val outDir = sakilaOutDir.get().asFile.resolve("demo").apply { mkdirs() }
        val db = outDir.resolve("sakila.db").apply { delete() }
        // DriverManager 대신 드라이버를 직접 써서 Gradle 클래스로더 문제를 피한다
        org.sqlite.JDBC().connect("jdbc:sqlite:${db.absolutePath}", Properties())!!.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(src.resolve("sqlite-sakila-schema.sql").readText())
                // 원본 스키마의 트리거는 INSERT 때 last_update를 '지금 시각'으로 덮어쓴다.
                // 읽기 전용 데모에는 필요 없고, 원본 데이터 그대로 두기 위해 데이터를 넣기 전에 지운다.
                val triggers = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'trigger'").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
                triggers.forEach { st.executeUpdate("DROP TRIGGER \"$it\"") }
                st.executeUpdate("PRAGMA journal_mode = OFF")
                st.executeUpdate("PRAGMA synchronous = OFF")
                st.executeUpdate("BEGIN")
                st.executeUpdate(src.resolve("sqlite-sakila-insert-data.sql").readText())
                st.executeUpdate("COMMIT")
                st.executeUpdate("VACUUM")
            }
        }
    }
}

sourceSets.main {
    resources.srcDir(buildSakilaDb)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-parameters"))
}
