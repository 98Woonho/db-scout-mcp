package io.github._98woonho.dbscout.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * db-scout 설정 ({@code db-scout.*}).
 *
 * <p>접속 정보가 하나도 없으면 내장 Sakila 데모 DB로 실행된다.
 * 접속 하나는 환경변수 {@code DB_SCOUT_URL}, {@code DB_SCOUT_USERNAME}, {@code DB_SCOUT_PASSWORD}로,
 * 여러 개는 설정 파일의 {@code db-scout.connections} 목록으로 등록한다.
 */
@ConfigurationProperties("db-scout")
public record DbScoutProperties(
        @DefaultValue List<ConnectionConfig> connections,
        @DefaultValue Limits limits) {

    /**
     * DB 접속 하나. password는 도구 응답·로그에 절대 내보내지 않는다.
     *
     * @param maskColumns   기본 규칙 외에 가릴 컬럼. {@code 테이블.컬럼} 또는 {@code 컬럼}, 뒤에 {@code =secret} 등으로 방식 지정
     * @param unmaskColumns 기본 규칙에 걸려도 가리지 않을 컬럼
     * @param hiddenTables  도구에 보이지 않고 조회도 막을 테이블. {@code 테이블} 또는 {@code 스키마.테이블}
     */
    public record ConnectionConfig(
            String name,
            String url,
            String username,
            String password,
            String description,
            List<String> maskColumns,
            List<String> unmaskColumns,
            List<String> hiddenTables) {

        /** 가리기·숨기기 설정 없이 만든다(테스트용). 생성자를 하나만 두어야 Spring이 설정을 바인딩할 수 있다. */
        public static ConnectionConfig of(String name, String url, String username, String password, String description) {
            return new ConnectionConfig(name, url, username, password, description, List.of(), List.of(), List.of());
        }

        public boolean isConfigured() {
            return url != null && !url.isBlank();
        }

        @Override
        public String toString() { // 실수로 로그에 찍혀도 비밀번호·주소가 나가지 않게
            return "ConnectionConfig[name=" + name + "]";
        }
    }

    /**
     * 조회 제한. 도구 호출에서 더 작은 값은 요청할 수 있지만 max 값을 넘을 수는 없다.
     *
     * @param defaultRows         행 수를 지정하지 않았을 때 돌려주는 행 수
     * @param maxRows             돌려줄 수 있는 최대 행 수
     * @param defaultTimeoutSeconds 실행 시간 기본 제한(초)
     * @param maxTimeoutSeconds   실행 시간 최대 제한(초)
     * @param maxCellChars        셀 하나의 최대 글자 수(넘으면 자름)
     * @param maxResponseChars    응답 전체의 최대 글자 수(Claude의 대화 공간 보호)
     */
    public record Limits(
            @DefaultValue("100") int defaultRows,
            @DefaultValue("1000") int maxRows,
            @DefaultValue("10") int defaultTimeoutSeconds,
            @DefaultValue("60") int maxTimeoutSeconds,
            @DefaultValue("200") int maxCellChars,
            @DefaultValue("50000") int maxResponseChars) {

        public Limits {
            requirePositive("default-rows", defaultRows);
            requirePositive("max-rows", maxRows);
            requirePositive("default-timeout-seconds", defaultTimeoutSeconds);
            requirePositive("max-timeout-seconds", maxTimeoutSeconds);
            requirePositive("max-cell-chars", maxCellChars);
            requirePositive("max-response-chars", maxResponseChars);
            if (defaultRows > maxRows) {
                throw new IllegalArgumentException("db-scout.limits.default-rows가 max-rows보다 큽니다");
            }
            if (defaultTimeoutSeconds > maxTimeoutSeconds) {
                throw new IllegalArgumentException(
                        "db-scout.limits.default-timeout-seconds가 max-timeout-seconds보다 큽니다");
            }
        }

        private static void requirePositive(String name, int value) {
            if (value <= 0) {
                throw new IllegalArgumentException("db-scout.limits." + name + "는 1 이상이어야 합니다: " + value);
            }
        }
    }
}
