package io.github._98woonho.dbscout.guard;

/** SQL을 실행하지 않은 이유. */
public enum RejectReason {
    EMPTY("빈 SQL"),
    TOO_LONG("너무 긴 SQL"),
    UNTERMINATED("닫히지 않은 따옴표·주석"),
    AMBIGUOUS_SYNTAX("DB마다 해석이 갈리는 문법"),
    PARSE_ERROR("분석할 수 없는 SQL"),
    MULTIPLE_STATEMENTS("여러 문장"),
    NOT_SELECT("SELECT가 아닌 문장"),
    WRITE_IN_QUERY("쿼리 안의 쓰기"),
    SELECT_INTO("SELECT INTO"),
    LOCKING("행 잠금"),
    FORBIDDEN_FUNCTION("금지된 함수"),
    FORBIDDEN_TABLE("금지된 테이블");

    private final String title;

    RejectReason(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
