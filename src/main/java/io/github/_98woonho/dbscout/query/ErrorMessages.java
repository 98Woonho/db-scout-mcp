package io.github._98woonho.dbscout.query;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DB 오류 메시지를 Claude에게 보여 주기 전에 정리한다.
 *
 * <p>DB 오류에는 데이터 값이 들어갈 수 있다. 예: {@code invalid input syntax for type integer: "mary@example.com"}.
 * 이러면 가리기를 우회할 수 있으므로, 따옴표 안의 글자가 <b>SQL에 없던 것</b>(=DB에서 나온 값)이면 숨긴다.
 * 컬럼·테이블 이름처럼 SQL에 있던 글자는 남겨 Claude가 오타를 고칠 수 있게 한다.
 */
final class ErrorMessages {

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"|'([^']*)'|`([^`]*)`");
    static final int MAX_LENGTH = 500;

    private ErrorMessages() {
    }

    static String sanitize(String message, String sql) {
        if (message == null || message.isBlank()) return "(메시지 없음)";
        String lowerSql = sql == null ? "" : sql.toLowerCase(Locale.ROOT);
        Matcher m = QUOTED.matcher(message);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String inner = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            String quote = m.group().substring(0, 1);
            boolean fromSql = inner.isBlank() || lowerSql.contains(inner.toLowerCase(Locale.ROOT));
            String replacement = fromSql ? m.group() : quote + "(값 숨김)" + quote;
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        String result = out.toString().strip();
        return result.length() > MAX_LENGTH ? result.substring(0, MAX_LENGTH) + "…" : result;
    }
}
