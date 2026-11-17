package io.github._98woonho.dbscout.connection;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 지원하는 DB 종류. JDBC URL 앞부분으로 판별한다. */
public enum DbKind {
    SQLITE("SQLite", List.of()),
    // 여러 문장 실행, 클라이언트 파일 읽기(LOAD DATA LOCAL), 객체 역직렬화, 사용자 코드 끼워 넣기
    MYSQL("MySQL", List.of("allowMultiQueries", "allowLoadLocalInfile", "allowLoadLocalInfileInPath",
            "allowUrlInLocalInfile", "autoDeserialize", "queryInterceptors", "exceptionInterceptors",
            "connectionLifecycleInterceptors", "statementInterceptors")),
    // 드라이버가 URL에 적힌 클래스를 만들어 실행하는 옵션들
    POSTGRESQL("PostgreSQL", List.of("socketFactory", "socketFactoryArg", "sslfactory", "sslfactoryarg",
            "sslhostnameverifier", "sslpasswordcallback", "authenticationPluginClassName", "loggerFile"));

    private final String displayName;
    private final List<String> forbiddenUrlOptions;

    DbKind(String displayName, List<String> forbiddenUrlOptions) {
        this.displayName = displayName;
        this.forbiddenUrlOptions = forbiddenUrlOptions;
    }

    public String displayName() {
        return displayName;
    }

    /** 지원하지 않는 URL이면 예외. 안전장치가 DB마다 다르므로 모르는 DB는 받지 않는다. */
    public static DbKind fromJdbcUrl(String url) {
        String u = url.trim().toLowerCase(Locale.ROOT);
        if (u.startsWith("jdbc:sqlite:")) return SQLITE;
        if (u.startsWith("jdbc:mysql:")) return MYSQL;
        if (u.startsWith("jdbc:postgresql:")) return POSTGRESQL;
        throw new IllegalArgumentException(
                "지원하지 않는 JDBC URL입니다. jdbc:sqlite:, jdbc:mysql:, jdbc:postgresql: 중 하나여야 합니다.");
    }

    /** 안전장치를 무력화할 수 있는 URL 옵션이 있으면 예외. */
    public void validateUrl(String url) {
        for (String option : forbiddenUrlOptions) {
            Pattern p = Pattern.compile("[?&;]" + Pattern.quote(option) + "\\s*=", Pattern.CASE_INSENSITIVE);
            if (p.matcher(url).find()) {
                throw new IllegalArgumentException(
                        "보안을 위해 " + displayName + " 접속 URL에 " + option + " 옵션을 쓸 수 없습니다.");
            }
        }
    }
}
