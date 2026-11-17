package io.github._98woonho.dbscout.query;

/** 검사는 통과했지만 실행 중에 실패한 쿼리. 메시지는 Claude에게 그대로 보여 줘도 되도록 정리돼 있다. */
public class QueryFailedException extends RuntimeException {

    public enum Kind {
        TIMEOUT("시간 초과"),
        CONNECTION("접속 실패"),
        READ_ONLY_VIOLATION("읽기 전용 위반"),
        DB_ERROR("DB 오류");

        final String title;

        Kind(String title) {
            this.title = title;
        }
    }

    private final Kind kind;

    public QueryFailedException(Kind kind, String detail) {
        super("[" + kind.title + "] " + detail);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
