package io.github._98woonho.dbscout.query;

import java.util.List;

/**
 * 조회 결과. 행은 컬럼 순서대로의 값 배열이다(객체보다 짧아 Claude의 대화 공간을 덜 쓴다).
 *
 * @param truncated       행 수·응답 크기 제한 때문에 결과가 잘렸으면 true. 실제 행은 더 있을 수 있다.
 * @param truncatedReason 잘린 이유
 * @param maskedColumns   개인정보로 보고 가린 컬럼
 * @param truncatedCells  글자 수 제한으로 잘린 셀 개수
 * @param notes           제한값 조정 등 알아 둘 점
 */
public record QueryResult(
        List<String> columns,
        List<List<Object>> rows,
        int rowCount,
        boolean truncated,
        String truncatedReason,
        long elapsedMs,
        List<MaskedColumn> maskedColumns,
        int truncatedCells,
        List<String> notes) {

    public record MaskedColumn(String column, String method) {
    }
}
