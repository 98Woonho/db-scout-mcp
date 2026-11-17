package io.github._98woonho.dbscout.connection;

import java.util.Locale;
import java.util.regex.Pattern;

/** 테이블·컬럼 이름 다루기: 비교용 정규화, SQL에 넣을 때 따옴표 처리. */
public final class Identifiers {

    private static final Pattern PLAIN = Pattern.compile("[a-z_][a-z0-9_]*");

    private Identifiers() {
    }

    /** 따옴표를 벗기고 소문자로(비교용). */
    public static String normalize(String identifier) {
        if (identifier == null) return null;
        String s = identifier.strip();
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("`") && s.endsWith("`")
                || s.startsWith("[") && s.endsWith("]"))) {
            s = s.substring(1, s.length() - 1);
        }
        return s.toLowerCase(Locale.ROOT);
    }

    /** SQL에 넣을 이름. 항상 따옴표로 감싸 예약어·대소문자·특수문자 문제를 피한다. */
    public static String quote(DbKind kind, String name) {
        return kind == DbKind.MYSQL
                ? "`" + name.replace("`", "``") + "`"
                : "\"" + name.replace("\"", "\"\"") + "\"";
    }

    public static String quote(DbKind kind, String schema, String name) {
        return schema == null || schema.isBlank() ? quote(kind, name) : quote(kind, schema) + "." + quote(kind, name);
    }

    /** 사람이 읽을 SQL 예시용: 소문자·밑줄로만 된 이름은 그대로, 아니면 따옴표. */
    public static String readable(DbKind kind, String name) {
        return PLAIN.matcher(name).matches() ? name : quote(kind, name);
    }
}
