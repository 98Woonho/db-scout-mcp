package io.github._98woonho.dbscout.guard;

/**
 * 안전 검사에서 막힌 SQL. 메시지는 Claude가 읽고 SQL을 고칠 수 있도록 이유와 대안을 담는다.
 */
public class QueryRejectedException extends RuntimeException {

    private final RejectReason reason;

    public QueryRejectedException(RejectReason reason, String detail) {
        super("[" + reason.title() + "] " + detail);
        this.reason = reason;
    }

    public RejectReason reason() {
        return reason;
    }
}
