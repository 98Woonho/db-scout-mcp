package io.github._98woonho.dbscout.connection;

import java.util.Locale;

/** 지원하는 DB 종류. JDBC URL 앞부분으로 판별한다. */
public enum DbKind {
    SQLITE("SQLite"),
    MYSQL("MySQL"),
    POSTGRESQL("PostgreSQL");

    private final String displayName;

    DbKind(String displayName) {
        this.displayName = displayName;
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
}
