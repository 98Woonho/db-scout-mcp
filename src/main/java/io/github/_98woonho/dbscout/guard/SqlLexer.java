package io.github._98woonho.dbscout.guard;

import io.github._98woonho.dbscout.connection.DbKind;

/**
 * SQL에서 주석을 지우고, DB마다 해석이 갈리는 문법을 막는 문자 단위 분석기.
 *
 * <p>왜 필요한가: SQL 분석기(JSqlParser)와 실제 DB가 같은 문자열을 다르게 읽으면 검사를 우회할 수 있다.
 * 예를 들어 MySQL은 {@code /*! ... *}{@code /} 안의 내용을 실행하지만 분석기는 주석으로 본다.
 * 그래서 주석을 여기서 모두 지우고, <b>지운 결과를 분석기와 DB에 똑같이</b> 보낸다.
 *
 * <p>문자열이 어디서 끝나는지가 DB 설정에 따라 달라지는 경우(역슬래시 이스케이프, 달러 따옴표)는
 * 분석기와 DB가 다르게 읽을 수 있으므로 막는다.
 */
final class SqlLexer {

    /** 주석을 지운 SQL. 끝의 세미콜론과 공백도 뺀다. */
    record Result(String sql, boolean hasStatementSeparator) {
    }

    private SqlLexer() {
    }

    static Result clean(String sql, DbKind kind) {
        StringBuilder out = new StringBuilder(sql.length());
        int lastSemicolon = -1; // out 기준 위치
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';

            if (c == '\0') {
                throw reject(RejectReason.AMBIGUOUS_SYNTAX, "SQL에 NUL 문자가 들어 있습니다.");
            }
            if (c == '\'' || c == '"' || c == '`') {
                int end = scanQuoted(sql, i, c);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '[' && kind == DbKind.SQLITE) { // SQLite는 [이름] 형식도 쓴다
                int end = sql.indexOf(']', i + 1);
                if (end < 0) throw unterminated("대괄호([ ])");
                out.append(sql, i, end + 1);
                i = end + 1;
                continue;
            }
            if (c == '$' && kind == DbKind.POSTGRESQL && isDollarQuoteStart(sql, i)) {
                throw reject(RejectReason.AMBIGUOUS_SYNTAX,
                        "PostgreSQL의 달러 따옴표 문자열($$...$$)은 지원하지 않습니다. 작은따옴표 문자열을 쓰세요.");
            }
            if (c == '/' && next == '/') {
                // 어느 DB에서도 정상 SQL에는 나오지 않지만, 분석기가 주석으로 읽을 수 있어 막는다
                throw reject(RejectReason.AMBIGUOUS_SYNTAX, "따옴표 밖의 `//`는 쓸 수 없습니다.");
            }
            if (c == '-' && next == '-') {
                if (kind == DbKind.MYSQL && i + 2 < n && !isMySqlCommentSpace(sql.charAt(i + 2))) {
                    // MySQL에서 "--1"은 주석이 아니라 "- -1"이다. 분석기는 주석으로 읽으므로 해석이 갈린다.
                    throw reject(RejectReason.AMBIGUOUS_SYNTAX,
                            "MySQL에서 `--` 바로 뒤에 공백이 없으면 주석이 아니라 빼기 연산으로 해석됩니다. "
                                    + "주석이면 `-- `처럼 공백을 넣고, 빼기면 `- -`로 쓰세요.");
                }
                i = skipLine(sql, i);
                out.append(' ');
                continue;
            }
            if (c == '#' && kind == DbKind.MYSQL) { // MySQL은 #부터 줄 끝까지 주석
                i = skipLine(sql, i);
                out.append(' ');
                continue;
            }
            if (c == '/' && next == '*') {
                i = skipBlockComment(sql, i, kind == DbKind.POSTGRESQL);
                out.append(' ');
                continue;
            }
            if (c == ';') {
                lastSemicolon = out.length();
            }
            out.append(c);
            i++;
        }

        // 끝에 붙은 세미콜론(과 공백)은 문장 구분자가 아니므로 뺀다
        int end = out.length();
        while (end > 0 && (Character.isWhitespace(out.charAt(end - 1)) || out.charAt(end - 1) == ';')) {
            end--;
        }
        String cleaned = out.substring(0, end).strip();
        return new Result(cleaned, lastSemicolon >= 0 && lastSemicolon < end);
    }

    /** 따옴표로 감싼 문자열·이름을 끝까지 읽는다. 같은 따옴표 두 개는 이스케이프. */
    private static int scanQuoted(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            if (c == '\\') {
                // MySQL(기본 설정)과 PostgreSQL의 E'' 문자열은 \' 를 따옴표로 읽어 문자열이 더 길어진다.
                // 분석기와 DB가 문자열 끝을 다르게 볼 수 있으므로 막는다.
                throw reject(RejectReason.AMBIGUOUS_SYNTAX,
                        "따옴표 안의 역슬래시(\\)는 DB 설정에 따라 해석이 달라 막습니다. "
                                + "작은따옴표는 '' 처럼 두 번 쓰고, 정규식이면 [0-9] 같은 표현을 쓰세요.");
            }
            i++;
        }
        throw unterminated(quote == '\'' ? "작은따옴표(')" : quote == '"' ? "큰따옴표(\")" : "백틱(`)");
    }

    /**
     * $$ 또는 $태그$ 인지. 앞 글자와 상관없이 본다. "1$$...$$"처럼 숫자 뒤에 붙어도 PostgreSQL은
     * 문자열로 읽을 수 있기 때문이다. 이름에 $$가 들어가는 드문 경우도 함께 막힌다.
     */
    private static boolean isDollarQuoteStart(String sql, int i) {
        int j = i + 1;
        if (j < sql.length() && sql.charAt(j) == '$') return true; // $$
        if (j >= sql.length() || !(Character.isLetter(sql.charAt(j)) || sql.charAt(j) == '_')) return false; // $1 등
        while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) j++;
        return j < sql.length() && sql.charAt(j) == '$';
    }

    private static boolean isMySqlCommentSpace(char c) {
        return Character.isWhitespace(c) || Character.isISOControl(c);
    }

    private static int skipLine(String sql, int i) {
        int nl = sql.indexOf('\n', i);
        return nl < 0 ? sql.length() : nl + 1;
    }

    /** 블록 주석을 건너뛴다. PostgreSQL은 주석 안에 주석을 넣을 수 있다. */
    private static int skipBlockComment(String sql, int start, boolean nested) {
        int depth = 1;
        int i = start + 2;
        while (i < sql.length() - 1) {
            char c = sql.charAt(i);
            char next = sql.charAt(i + 1);
            if (c == '*' && next == '/') {
                depth--;
                i += 2;
                if (depth == 0 || !nested) return i;
                continue;
            }
            if (nested && c == '/' && next == '*') {
                depth++;
                i += 2;
                continue;
            }
            i++;
        }
        throw unterminated("블록 주석(/* */)");
    }

    private static QueryRejectedException unterminated(String what) {
        return reject(RejectReason.UNTERMINATED, "닫히지 않은 부분이 있습니다: " + what);
    }

    private static QueryRejectedException reject(RejectReason reason, String detail) {
        return new QueryRejectedException(reason, detail);
    }
}
